/*******************************************************************************
 * Copyright (c) 2016, 2023 IBH SYSTEMS GmbH and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License 2.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-2.0
 *
 * Contributors:
 *     IBH SYSTEMS GmbH - initial API and implementation
 *     Red Hat Inc - upgrade to package drone 0.14.0, enhance features
 *     Bernd Warmuth - bugfix target folder creation
 *     Oliver Richter - Made packageName & defaultScriptInterpreter configurable
 *     Lucian Burja - Added setting for creating relocatable RPM packages
 *     Peter Wilkinson - add skip entry flag
 *     Daniel Singhal - Added primary artifact support
 *     Jarkko Sonninen - Added outputFileNameProperty
 *     Fraunhofer-Institut fuer Optronik, Systemtechnik und Bildauswertung IOSB - apply rules to implicit created intermediate directories
 *******************************************************************************/
package de.dentrassi.rpm.builder;

import static java.nio.charset.StandardCharsets.US_ASCII;
import static java.nio.file.Files.readAllLines;

import java.io.File;
import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.apache.maven.archiver.MavenArchiver;
import org.apache.maven.model.License;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.MojoFailureException;
import org.apache.maven.plugins.annotations.Component;
import org.apache.maven.plugins.annotations.LifecyclePhase;
import org.apache.maven.plugins.annotations.Mojo;
import org.apache.maven.plugins.annotations.Parameter;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.MavenProjectHelper;
import org.bouncycastle.openpgp.PGPPrivateKey;
import org.codehaus.plexus.util.DirectoryScanner;
import org.eclipse.packager.rpm.Architecture;
import org.eclipse.packager.rpm.HashAlgorithm;
import org.eclipse.packager.rpm.OperatingSystem;
import org.eclipse.packager.rpm.RpmLead;
import org.eclipse.packager.rpm.RpmTag;
import org.eclipse.packager.rpm.RpmVersion;
import org.eclipse.packager.rpm.build.*;
import org.eclipse.packager.rpm.build.RpmBuilder.PackageInformation;
import org.eclipse.packager.rpm.build.RpmBuilder.Version;
import org.eclipse.packager.rpm.deps.RpmDependencyFlags;
import org.eclipse.packager.rpm.header.Header;
import org.eclipse.packager.rpm.info.RpmInformation;
import org.eclipse.packager.rpm.signature.RsaHeaderSignatureProcessor;
import org.eclipse.packager.rpm.signature.RsaSignatureProcessor;
import org.eclipse.packager.rpm.signature.SignatureProcessor;

import com.google.common.base.Strings;
import com.google.common.io.CharSource;

import de.dentrassi.rpm.builder.Naming.Case;
import de.dentrassi.rpm.builder.PackageEntry.Collector;
import de.dentrassi.rpm.builder.signatures.SignatureConfiguration;

/**
 * Build an RPM file
 *
 * @author ctron
 */
@Mojo(name = "rpm", defaultPhase = LifecyclePhase.PACKAGE, requiresProject = true, threadSafe = true)
public class RpmMojo extends AbstractMojo {
    private static final String SNAPSHOT_SUFFIX = "-SNAPSHOT";

    /**
     * The maven project
     */
    @Parameter(property = "project", readonly = true, required = true)
    protected MavenProject project;

    @Component
    MavenProjectHelper projectHelper;

    /**
     * The version string to be processed in case of a release build
     *
     * @see #snapshotVersion
     */
    @Parameter(defaultValue = "${project.version}")
    String version;

    /**
     * The version string to be processed in case of a SNAPSHOT build
     *
     * @see #version
     */
    @Parameter(property = "rpm.snapshotVersion")
    String snapshotVersion;

    /**
     * The RPM package name
     */
    @Parameter(defaultValue = "${project.artifactId}", property = "rpm.packageName")
    String packageName;

    /**
     * The architecture
     */
    @Parameter(defaultValue = "noarch", property = "rpm.architecture")
    String architecture = "noarch";

    /**
     * Override the lead architecture value.
     * <p>
     * Also see <a href="lead.html">Lead information</a>.
     * </p>
     *
     * @since 0.10.2
     */
    @Parameter(property = "rpm.leadOverride.architecture")
    Architecture leadOverrideArchitecture;

    public void setLeadOverrideArchitecture(final Architecture leadOverrideArchitecture) {
        this.leadOverrideArchitecture = leadOverrideArchitecture;
    }

    /**
     * The "operatingSystem" field in the RPM file.
     *
     * @since 0.10.2
     */
    @Parameter(property = "rpm.operatingSystem")
    String operatingSystem = "linux";

    public void setOperatingSystem(final String operatingSystem) {
        this.operatingSystem = operatingSystem;
    }

    /**
     * Override the lead operating system value.
     * <p>
     * Also see <a href="lead.html">Lead information</a>.
     * </p>
     *
     * @since 0.10.2
     */
    @Parameter(property = "rpm.leadOverride.operatingSystem")
    OperatingSystem leadOverrideOperatingSystem;

    public void setLeadOverrideOperatingSystem(final OperatingSystem leadOverrideOperatingSystem) {
        this.leadOverrideOperatingSystem = leadOverrideOperatingSystem;
    }

    /**
     * Set the name of the source package.
     *
     * @since 0.11.0
     */
    @Parameter(property = "rpm.sourcePackage")
    String sourcePackage;

    public void setSourcePackage(final String sourcePackage) {
        this.sourcePackage = sourcePackage;
    }

    /**
     * Whether to generate a default source package.
     * <p>
     * If the {@code sourceProperty} package is {@code null} or empty, this flag
     * controls if a default value is generated for the source package or not.
     * </p>
     * <p>
     * The default name will consist of the package name and the version with
     * the suffix {@code .src.rpm}. The output file name is not used as a basis
     * for the default source package name. If you need more control over the
     * source package name you need to use the {@code sourcePackage} property
     * instead.
     * </p>
     * <p>
     * Before version 0.11.0 the source package was always empty. The new
     * default behavior is to fill the source package with a package name
     * derived from the package name. Setting this flag to {@code false} will
     * revert to the old default behavior of leaving the source package header
     * field unset.
     * </p>
     *
     * @since 0.11.0
     */
    @Parameter(property = "rpm.generateDefaultSourcePackage", defaultValue = "true")
    boolean generateDefaultSourcePackage = true;

    public void setGenerateDefaultSourcePackage(final boolean generateDefaultSourcePackage) {
        this.generateDefaultSourcePackage = generateDefaultSourcePackage;
    }

    /**
     * The prefix of the release if this is a snapshot build, will be suffixed
     * with the snapshot build id
     * <p>
     * Also see: {@link #snapshotBuildId}
     * </p>
     */
    @Parameter(defaultValue = "0.", property = "rpm.snapshotReleasePrefix")
    String snapshotReleasePrefix = "0.";

    /**
     * Set the build id which is used when a snapshot build is active.
     * <p>
     * If this parameter is left unset or empty then the current time (UTC) in
     * the format {@code yyyyMMddHHmm} will be used.
     * </p>
     *
     * @since 0.6.0
     */
    @Parameter(property = "rpm.snapshotBuildId", required = false)
    String snapshotBuildId;

    /**
     * The release which will be used if this is not a snapshot build
     */
    @Parameter(property = "rpm.release", defaultValue = "1")
    String release = "1";

    /**
     * Always use the "release" string
     * <p>
     * This parameter enforces the build to always use the RPM release
     * information from the parameter "release", whether this is a snapshot
     * build or not
     * </p>
     *
     * @since 0.6.0
     */
    @Parameter(property = "rpm.forceRelease", defaultValue = "false")
    boolean forceRelease = false;

    /**
     * The classifier of the attached rpm
     */
    @Parameter(property = "rpm.classifier", defaultValue = "rpm")
    String classifier = "rpm";

    /**
     * Whether to attach the output file
     */
    @Parameter(property = "rpm.attach", defaultValue = "true")
    boolean attach = true;

    /**
     * The RPM epoch, leave unset for default
     */
    @Parameter(property = "rpm.epoch")
    Integer epoch;

    /**
     * The "summary" field of the RPM file
     * <p>
     * This defaults to the project name
     * </p>
     */
    @Parameter(property = "rpm.summary", defaultValue = "${project.name}")
    String summary;

    /**
     * The "description" field of the RPM file
     * <p>
     * This defaults to the Maven project description
     * </p>
     */
    @Parameter(property = "rpm.description", defaultValue = "${project.description}")
    String description;

    /**
     * The RPM group
     * <p>
     * See also
     * <a href="https://fedoraproject.org/wiki/RPMGroups">https://fedoraproject.
     * org/wiki/RPMGroups</a>
     * </p>
     */
    @Parameter(property = "rpm.group", defaultValue = "Unspecified")
    String group;

    /**
     * The "distribution" field in the RPM file
     */
    @Parameter(property = "rpm.distribution")
    String distribution;

    /**
     * Whether the plugin should try to evaluate to hostname
     * <p>
     * If set to {@code false}, then he build hostname {@code localhost} will be
     * used instead of the actual hostname
     * </p>
     */
    @Parameter(property = "rpm.evalHostname", defaultValue = "true")
    boolean evalHostname = true;

    /**
     * The license of the RPM file
     * <p>
     * This defaults to a comma separated list of all license names specified in
     * the projects POM file.
     * </p>
     */
    @Parameter(property = "rpm.license")
    String license;

    /**
     * The vendor name of the RPM file
     * <p>
     * This defaults to the name of the organization in the POM file.
     * </p>
     */
    @Parameter(property = "rpm.vendor")
    String vendor;

    /**
     * The name of the packager in the RPM file
     * <p>
     * This defaults to <tt>${project.organization.name}
     * &lt;${project.organization.url}&gt;</tt> if both values are set.
     * </p>
     * <p>
     * See also <a href=
     * "http://www.rpm.org/max-rpm/s1-rpm-inside-tags.html#S3-RPM-INSIDE-PACKAGER-TAG">
     * http://www.rpm.org/max-rpm/s1-rpm-inside-tags.html#S3-RPM-INSIDE-PACKAGER
     * -TAG</a>
     * </p>
     */
    @Parameter(property = "rpm.packager")
    String packager;

    /**
     * Build relocatable packages.
     *
     * <pre>
     *       &lt;prefixes&gt;
     *           &lt;prefix&gt;/opt&lt;/prefix&gt;
     *           &lt;prefix>/var/log&lt;/prefix&gt;
     *       &lt;/prefixes&gt;
     * </pre>
     * <p>
     * See also
     * <a href="http://ftp.rpm.org/max-rpm/s1-rpm-reloc-prefix-tag.html">The
     * prefix tag</a>
     *
     * @since 1.1.0
     */
    @Parameter(property = "rpm.prefixes")
    List<String> prefixes;

    /**
     * Base paths for which implicitly created intermediate directories should
     * explicitly add to package.
     *
     * <pre>
     *       &lt;generateIntermediateDirectories&gt;
     *           &lt;baseDirectory&gt;/opt/mycompany/myapp&lt;/baseDirectory&gt;
     *           &lt;baseDirectory&gt;/etc/mycompany&lt;/baseDirectory&gt;
     *       &lt;/generateIntermediateDirectories&gt;
     * </pre>
     *
     * <p>
     * For these given base directories, any intermediate directories created
     * implicitly from {@code <entry>} elements are added to the package
     * as explicit entries.
     * The rules with file information are applied to these added directories as well.
     * </p>
     * <p>
     * Note: In case of {@code <collect>} entries this feature is only applied to
     * directories outside of the collection.
     * </p>
     *
     * @since 1.12.0
     */
    @Parameter(property = "rpm.generateIntermediateDirectories")
    List<String> generateIntermediateDirectories;

    /**
     * The actual payload/file entries
     * <p>
     * Also see <a href="entry.html">entries</a>
     * </p>
     * <p>
     * This is a list of {@code <entry>} elements with additional information:
     * </p>
     *
     * <pre>
     * &lt;entries&gt;
     *   &lt;entry&gt;
     *     &lt;!-- target name --&gt;
     *     &lt;name&gt;/etc/foo/bar.conf&lt;/name&gt;
     *
     *     &lt;!-- either one of:
     *       &lt;file&gt;src/main/resources/bar.conf&lt;/file&gt;
     *       &lt;directory&gt;true&lt;/directory&gt;
     *       &lt;collect&gt;
     *         &lt;from&gt;src/main/resources/dir&lt;/from&gt;
     *       &lt;/collect&gt;
     *     -->
     *
     *   &lt;/entry&gt;
     * &lt;/entries&gt;
     * </pre>
     */
    @Parameter
    List<PackageEntry> entries = new LinkedList<>();

    /**
     * Rulesets to configure the file information like "user", "modes", etc.
     * <p>
     * Also see <a href="rulesets.html">rulesets</a>.
     * </p>
     */
    @Parameter
    List<Ruleset> rulesets = new LinkedList<>();

    /**
     * The default ruleset to use if no other is specified
     */
    @Parameter
    String defaultRuleset;

    /**
     * A list of change-logs for this RPM
     */
    @Parameter
    List<Changelog> changelogs = new LinkedList<>();

    private Logger logger;

    private RulesetEvaluator eval;

    /**
     * A script which is run before the installation takes place
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script beforeInstallation;

    /**
     * A script which is run after the installation took place
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script afterInstallation;

    /**
     * A script which is run before the removal takes place
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script beforeRemoval;

    /**
     * A script which is run after the removal took place
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script afterRemoval;

    /**
     * A script which is run before the entire transaction starts
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script beforeTransaction;

    /**
     * A script which is run after the entire transaction finishes
     * <p>
     * Also see <a href="rulesets.html">scripts</a>
     * </p>
     */
    @Parameter
    Script afterTransaction;

    /**
     * The default script interpreter which is used if neither the script has
     * one set explicitly, nor one could be detected
     */
    @Parameter(property = "rpm.defaultScriptInterpreter", defaultValue = "/bin/sh")
    String defaultScriptInterpreter;

    /**
     * RPM package requirements
     * <p>
     * Also see <a href="deps.html">dependencies</a>
     * </p>
     */
    @Parameter
    List<Dependency> requires = new LinkedList<>();

    /**
     * RPM provides information
     * <p>
     * Also see <a href="deps.html">dependencies</a>
     * </p>
     */
    @Parameter
    List<SimpleDependency> provides = new LinkedList<>();

    /**
     * RPM package conflicts
     * <p>
     * Also see <a href="deps.html">dependencies</a>
     * </p>
     */
    @Parameter
    List<SimpleDependency> conflicts = new LinkedList<>();

    /**
     * RPM obsoletes information
     * <p>
     * Also see <a href="deps.html">dependencies</a>
     * </p>
     */
    @Parameter
    List<SimpleDependency> obsoletes = new LinkedList<>();

    /**
     * RPM package requirements needed before the installation starts
     * <p>
     * Also see <a href="deps.html">dependencies</a>
     * </p>
     */
    @Parameter
    List<SimpleDependency> prerequisites = new LinkedList<>();

    /**
     * Hint forward dependency.
     * <p>
     * Also see <a href="deps.html">dependencies</a> and
     * <a href="https://fedoraproject.org/wiki/Packaging:WeakDependencies">Weak
     * dependencies</a>.
     * </p>
     */
    @Parameter
    List<SimpleDependency> suggests = new LinkedList<>();

    /**
     * Hint backward dependency.
     * <p>
     * Also see <a href="deps.html">dependencies</a> and
     * <a href="https://fedoraproject.org/wiki/Packaging:WeakDependencies">Weak
     * dependencies</a>.
     * </p>
     */
    @Parameter
    List<SimpleDependency> enhances = new LinkedList<>();

    /**
     * Weak backward dependency.
     * <p>
     * Also see <a href="deps.html">dependencies</a> and
     * <a href="https://fedoraproject.org/wiki/Packaging:WeakDependencies">Weak
     * dependencies</a>.
     * </p>
     */
    @Parameter
    List<SimpleDependency> supplements = new LinkedList<>();

    /**
     * Weak forward dependency.
     * <p>
     * Also see <a href="deps.html">dependencies</a> and
     * <a href="https://fedoraproject.org/wiki/Packaging:WeakDependencies">Weak
     * dependencies</a>.
     * </p>
     */
    @Parameter
    List<SimpleDependency> recommends = new LinkedList<>();

    /**
     * An optional signature descriptor for GPG signing the final RPM
     * <p>
     * Also see <a href="signing.html">signing</a>
     * </p>
     */
    @Parameter(property = "rpm.signature")
    Signature signature;

    /**
     * Disable the mojo altogether.
     *
     * @since 1.1.1
     */
    @Parameter(property = "rpm.skip", defaultValue = "false")
    boolean skip = false;

    public void setSkip(final boolean skip) {
        this.skip = skip;
    }

    /**
     * Disable all package signing
     */
    @Parameter(property = "rpm.skipSigning", defaultValue = "false")
    boolean skipSigning = false;

    public void setSkipSigning(final boolean skipSigning) {
        this.skipSigning = skipSigning;
    }

    /**
     * Provide package naming options
     * <p>
     * Also see <a href="naming.html">naming</a>
     * </p>
     */
    @Parameter(property = "rpm.naming")
    Naming naming;

    public void setNaming(final Naming naming) {
        this.naming = naming;
    }

    /**
     * The location to place the RPM file into.
     *
     * @since 0.10.1
     */
    @Parameter(property = "rpm.targetDir", defaultValue = "${project.build.directory}")
    File targetDir;

    public void setTargetDir(final File targetDir) {
        this.targetDir = targetDir;
    }

    /**
     * The file name of the output file.
     * <p>
     * This defaults the to an internal builder which will use
     * {@code <packageName>-<version>-<release>.<arch>.rpm}.
     * Also see <a href="naming.html">naming</a>.
     * </p>
     * <p>
     * Using this override will completely disable the internal
     * name builder and simply use the provided value.
     * </p>
     *
     * @since 0.10.1
     */
    @Parameter(property = "rpm.outputFileName")
    String outputFileName;

    public void setOutputFileName(final String outputFileName) {
        this.outputFileName = outputFileName;
    }

    /**
     * Name of the property, which is set according to the final outputFileName
     *
     * @since 1.9.1
     */
    @Parameter(property = "rpm.outputFileNameProperty", defaultValue="project.build.rpm.outputFileName")
    String outputFileNameProperty = "project.build.rpm.outputFileName";

    public void setOutputFileNameProperty(final String outputFileNameProperty) {
        this.outputFileNameProperty = outputFileNameProperty;
    }

    /**
     * The highest supported RPM version this package must conform to.
     * <p>
     * This allows to set a maximum version of RPM this package must be
     * compatible with. If unset, it will not check the required RPM version.
     * </p>
     *
     * @since 0.11.0
     */
    @Parameter(property = "rpm.maximumSupportedRpmVersion")
    Version maximumSupportedRpmVersion;

    public void setMaximumSupportedRpmVersion(final Version maximumSupportedRpmVersion) {
        this.maximumSupportedRpmVersion = maximumSupportedRpmVersion;
    }

    public void setMaximumSupportedRpmVersion(final String maximumSupportedRpmVersion) {
        this.maximumSupportedRpmVersion = Version.fromVersionString(maximumSupportedRpmVersion).orElseThrow(() -> new IllegalArgumentException(String.format("Version '%s' is unknown", maximumSupportedRpmVersion)));
    }

    /**
     * Specify the "hint" for a provider of a signature configuration.
     * <p>
     * By default, the RPM writer will calculate and add information like MD5,
     * SHA1, SHA256, etc. to the signature header of the RPM file. However,
     * some, especially older (really old) RPM versions, have issues when the
     * encounter signature information they don't understand. This parameter
     * allows you to configure this process.
     * <p>
     * What you configure here is the so-called "hint" (Plexus component
     * name/hint) of the provider to use. This plexus component has to be found
     * in the class path of the plugin, during runtime. There are two default
     * providers available, one is <code>default</code> and the other is
     * <code>md5-only</code>. The latter only adds MD5 checksum information.
     * <p>
     * The default is to add as much information as possible to the RPM. So
     * normally you don't need this parameter.
     * <p>
     * Specifying a configuration provider which cannot be found during the
     * build, will fail the build.
     *
     * @since 1.4.0
     */
    @Parameter
    String signatureConfiguration;

    public void setSignatureConfiguration(final String signatureConfiguration) {
        this.signatureConfiguration = signatureConfiguration;
    }

    /**
     * Timestamp for reproducible output archive entries, either formatted as ISO 8601
     * <code>yyyy-MM-dd'T'HH:mm:ssXXX</code> or as an int representing seconds since the epoch (like
     * <a href="https://reproducible-builds.org/docs/source-date-epoch/">SOURCE_DATE_EPOCH</a>).
     *
     * @since XXX
     */
    @Parameter(defaultValue = "${project.build.outputTimestamp}")
    String outputTimestamp;

    /**
     * Configure the digest algorithm for files.
     *
     * <p>
     * This configures the algorithm which is used to calculate a digest for each file. This information is stored
     * (per file) in the RPM header section.
     * </p>
     *
     * <p>
     * The following variants are possible (from {@link DigestAlgorithm}, in no particular order):
     * </p>
     * <ul>
     *     <li><code>MD2</code></li>
     *     <li><code>MD5</code></li>
     *     <li><code>SHA1</code></li>
     *     <li><code>Double-SHA</code></li>
     *     <li><code>SHA-224</code></li>
     *     <li><code>SHA-256</code> (default)</li>
     *     <li><code>SHA-384</code></li>
     *     <li><code>SHA-512</code></li>
     *     <li><code>RIPE-MD160</code></li>
     *     <li><code>Tiger-192</code></li>
     *     <li><code>Haval-5-160</code></li>
     * </ul>
     *
     * <p>
     *     <strong>NOTE:</strong> This relies on the JVM to provide a {@code MessageDigest} provider. If you choose a
     *     file digest algorithm for which the JVM doesn't provide an implementation, the build will fail. By default,
     *     JVMs (as of 1.8) should at least support MD5, SHA1, and SHA256. In practice SHA-224 to SHA-512 are supported
     *     by most JVMs.
     * </p>
     *
     * <p>
     *     <strong>NOTE:</strong> This used to be <code>MD5</code> in releases before <code>1.10.0</code>. Starting
     *     with <code>1.10.0</code> this defaults to <code>SHA-256</code> and can be overridden using this setting.
     * </p>
     *
     * @since 1.10.0
     */
    @Parameter(defaultValue = "SHA-256", property = "rpm.fileDigestAlgorithm")
    String fileDigestAlgorithm;

    private Instant outputTimestampInstant;

    @Component(role = SignatureConfiguration.class)
    protected Map<String, SignatureConfiguration> signatureConfigurationProviders;

    @Override
    public void execute() throws MojoExecutionException, MojoFailureException {
        this.logger = new Logger(getLog());

        if (this.skip) {
            this.logger.debug("Skipping execution");
            return;
        }

        this.eval = new RulesetEvaluator(this.rulesets);

        final Path targetDir;

        if (this.targetDir != null) {
            targetDir = this.targetDir.toPath();
        } else {
            targetDir = Paths.get(this.project.getBuild().getDirectory());
        }

        if (!Files.exists(targetDir)) {
            try {
                Files.createDirectories(targetDir);
            } catch (final FileAlreadyExistsException e) {
                // silently ignore
            } catch (final IOException ioe) {
                this.logger.debug("Unable to create target directory %s", targetDir);
                throw new MojoExecutionException("RPM build failed.", ioe);

            }
        }

        this.outputTimestampInstant = MavenArchiver.parseBuildOutputTimestamp(this.outputTimestamp).orElse(null);
        if (this.outputTimestampInstant != null) {
            this.logger.info("Creating reproducible RPM at timestamp: %s", this.outputTimestampInstant);
        }

        final String outputFileName = makeTargetFilename();
        project.getProperties().setProperty(outputFileNameProperty, outputFileName);
        final Path targetFile = makeTargetFile(targetDir, outputFileName);

        this.logger.debug("Max supported RPM version: %s", this.maximumSupportedRpmVersion);

        this.logger.info("Writing to target to: %s", targetFile);
        this.logger.debug("Default script interpreter: %s", this.defaultScriptInterpreter);
        this.logger.debug("Default ruleset: %s", this.defaultRuleset);

        final String packageName = makePackageName();
        final RpmVersion version = makeVersion();

        this.logger.info("RPM base information - name: %s, version: %s, arch: %s", packageName, version, this.architecture);

        testLeadFlags();

        final BuilderOptions options = new BuilderOptions();
        DigestAlgorithm fileDigestAlgorithm = evalDigestAlgorithm(this.fileDigestAlgorithm);
        this.logger.info("File Digest Algorithm: %s", fileDigestAlgorithm.getAlgorithm());
        options.setFileDigestAlgorithm(fileDigestAlgorithm);

        // setup basic signature processors

        final SignatureConfiguration provider;
        if (this.signatureConfiguration != null) {
            this.logger.info("Initialize with custom signature configuration: %s (%s)", this.signatureConfiguration, this.signatureConfiguration.getClass());
            provider = this.signatureConfigurationProviders.get(this.signatureConfiguration);
            if (provider == null) {
                throw new MojoExecutionException(String.format("Unable to find requested signature configuration provider '%s', have: %s", this.signatureConfiguration, this.signatureConfigurationProviders.keySet()));
            }
            provider.applyOptions(options);
        } else {
            provider = null;
        }

        // start writing the RPM

        try (final RpmBuilder builder = new RpmBuilder(packageName, version, this.architecture, targetFile, options)) {
            this.logger.info("Writing target file: %s", builder.getTargetFile());

            if (this.leadOverrideArchitecture != null) {
                this.logger.info("Override RPM lead architecture: %s", this.leadOverrideArchitecture);
                builder.setLeadOverrideArchitecture(this.leadOverrideArchitecture);
            }
            if (this.leadOverrideOperatingSystem != null) {
                this.logger.info("Override RPM lead operating system: %s", this.leadOverrideOperatingSystem);
                builder.setLeadOverrideOperatingSystem(this.leadOverrideOperatingSystem);
            }

            fillPackageInformation(builder);
            fillScripts(builder);
            fillDependencies(builder);
            fillPayload(builder);
            customizeHeader(builder);

            // apply signature configuration, if we have one
            if (provider != null) {
                provider.applyBuilder(builder);
            }

            if (!this.skipSigning && this.signature != null) {
                final SignatureProcessor[] signers = makeRsaSigners(this.signature);
                if (signers != null) {
                    for (SignatureProcessor signer : signers) {
                        builder.addSignatureProcessor(signer);
                    }
                }
            }

            // finally build the file

            builder.build();

            // version check

            checkVersion(builder);

            // attach when necessary

            if (this.attach) {
                this.logger.info("attaching %s", this.classifier);
                if ("rpm".equals(this.project.getPackaging())) {
                    this.project.getArtifact().setFile(builder.getTargetFile().toFile());
                } else {
                    this.projectHelper.attachArtifact(this.project, "rpm", this.classifier, builder.getTargetFile().toFile());
                }

            }
        } catch (final IOException e) {
            throw new MojoExecutionException("Failed to write RPM", e);
        }
    }

    private DigestAlgorithm evalDigestAlgorithm(String algorithm) throws MojoFailureException {
        try {
            // try enum literal name first
            return DigestAlgorithm.valueOf(algorithm);
        }
        catch (IllegalArgumentException ignored) {}

        // try algorithm names next
        for (DigestAlgorithm a : DigestAlgorithm.values()) {
            if (a.getAlgorithm().equalsIgnoreCase(algorithm)) {
                return a;
            }
        }

        // fail
        throw new MojoFailureException(String.format("Unknown file digest algorithm: %s", algorithm));
    }

    private String makeTargetFilename() {
        String outputFileName = this.outputFileName;

        if (outputFileName == null || outputFileName.isEmpty()) {
            if (this.naming.getDefaultFormat() == Naming.DefaultFormat.LEGACY) {
                outputFileName = RpmFileNameProvider.LEGACY_FILENAME_PROVIDER.getRpmFileName(makePackageName(), makeVersion(), this.architecture);
            } else {
                outputFileName = RpmFileNameProvider.DEFAULT_FILENAME_PROVIDER.getRpmFileName(makePackageName(), makeVersion(), this.architecture);
            }
            this.logger.debug("Using generated file name - %s", outputFileName, outputFileName);
        }
        return outputFileName;
    }

    private Path makeTargetFile(final Path targetDir, final String outputFileName) {
        final Path targetFile = targetDir.resolve(outputFileName);
        this.logger.debug("Resolved output file name - fileName: %s, fullName: %s", this.outputFileName, targetFile);
        return targetFile;
    }

    protected void checkVersion(final RpmBuilder builder) throws MojoFailureException {
        final Version version = builder.getRequiredRpmVersion();
        this.logger.info("Required RPM version: %s", version);

        if (this.maximumSupportedRpmVersion == null) {
            return;
        }

        if (version.compareTo(this.maximumSupportedRpmVersion) > 0) {
            throw new MojoFailureException(builder.getTargetFile(), "Generated RPM file not compatible with version " + this.maximumSupportedRpmVersion, String.format("The generated RPM package would require at least version %1$s, however the build limits the supported RPM version to %2$s. Either raise the support RPM version or remove features requiring a more recent version of RPM.", version, this.maximumSupportedRpmVersion));
        }
    }

    private void testLeadFlags() {
        if (this.leadOverrideArchitecture == null) {
            final Optional<Architecture> arch = Architecture.fromAlias(this.architecture);
            if (!arch.isPresent()) {
                this.logger.warn("Architecture '%s' cannot be mapped to lead information. Consider using setting 'leadOverrideArchitecture'.", this.architecture);
            }
        }

        if (this.leadOverrideOperatingSystem == null) {
            final Optional<OperatingSystem> os = OperatingSystem.fromAlias(this.operatingSystem);
            if (!os.isPresent()) {
                this.logger.warn("OperatingSystem '%s' cannot be mapped to lead information. Consider using setting 'leadOverrideOperatingSystem'.", this.operatingSystem);
            }
        }

    }

    private SignatureProcessor[] makeRsaSigners(final Signature signature) throws MojoExecutionException, MojoFailureException {
        final PGPPrivateKey privateKey = SigningHelper.loadKey(signature, this.logger);
        if (privateKey == null) {
            return null;
        }
        return new SignatureProcessor[]{
                new RsaHeaderSignatureProcessor(privateKey, HashAlgorithm.from(signature.getHashAlgorithm())),
                new RsaSignatureProcessor(privateKey, HashAlgorithm.from(signature.getHashAlgorithm()))
        };
    }

    @FunctionalInterface
    private interface DependencyAdder {
        void add(String name, String version, RpmDependencyFlags[] flags);
    }

    private void fillDependencies(final RpmBuilder builder) {
        addAllDependencies("require", this.requires, builder::addRequirement, RpmMojo::validateName, null);
        addAllDependencies("prerequire", this.prerequisites, builder::addRequirement, RpmMojo::validateName, flags -> flags.add(RpmDependencyFlags.PREREQ));
        addAllDependencies("provide", this.provides, builder::addProvides, ((Consumer<SimpleDependency>) RpmMojo::validateName).andThen(this::validateNoVersion), null);
        addAllDependencies("conflict", this.conflicts, builder::addConflicts, RpmMojo::validateName, null);
        addAllDependencies("obsolete", this.obsoletes, builder::addObsoletes, RpmMojo::validateName, null);

        addAllDependencies("suggest", this.suggests, builder::addSuggests, RpmMojo::validateName, null);
        addAllDependencies("enhance", this.enhances, builder::addEnhances, RpmMojo::validateName, null);
        addAllDependencies("supplement", this.supplements, builder::addSupplements, RpmMojo::validateName, null);
        addAllDependencies("recommends", this.recommends, builder::addRecommends, RpmMojo::validateName, null);
    }

    private static void validateName(final SimpleDependency dep) {
        if (Strings.isNullOrEmpty(dep.getName())) {
            throw new IllegalStateException("'name' of dependency must be set");
        }
    }

    private void validateNoVersion(final SimpleDependency dep) {
        if (!Strings.isNullOrEmpty(dep.getVersion())) {
            getLog().warn(String.format("Provides should not have a version: %s : %s. Use at your own risk!", dep.getName(), dep.getVersion()));
        }
    }

    private <T extends SimpleDependency> void addAllDependencies(final String depName, final List<T> deps, final DependencyAdder adder, final Consumer<T> validator, final Consumer<Set<RpmDependencyFlags>> flagsCustomizer) {
        if (deps == null) {
            return;
        }

        for (final T dep : deps) {
            validator.accept(dep);

            final String name = dep.getName();
            final String version = dep.getVersion();
            final Set<RpmDependencyFlags> flags = dep.getFlags();

            if (flagsCustomizer != null) {
                flagsCustomizer.accept(flags);
            }

            this.logger.info("Adding dependency [%s]: name = %s, version = %s, flags = %s", depName, name, version, flags);

            adder.add(name, version, flags.toArray(new RpmDependencyFlags[0]));
        }
    }

    private void fillScripts(final RpmBuilder builder) throws IOException {
        setScript("prein", this.beforeInstallation, builder::setPreInstallationScript);
        setScript("postin", this.afterInstallation, builder::setPostInstallationScript);
        setScript("prerm", this.beforeRemoval, builder::setPreRemoveScript);
        setScript("postrm", this.afterRemoval, builder::setPostRemoveScript);
        setScript("pretrans", this.beforeTransaction, builder::setPreTransactionScript);
        setScript("posttrans", this.afterTransaction, builder::setPostTransactionScript);
    }

    private void setScript(final String scriptName, final Script script, final ScriptSetter setter) throws IOException {
        if (script == null) {
            return;
        }

        final String scriptContent = script.makeScriptContent();

        if (Strings.isNullOrEmpty(scriptContent)) {
            return;
        }

        String interpreter = script.getInterpreter();
        this.logger.debug("[script %s:]: explicit interpreter: %s", scriptName, interpreter);

        if (Strings.isNullOrEmpty(interpreter)) {
            interpreter = detectInterpreter(scriptContent);
            this.logger.debug("[script %s:]: detected interpreter: %s", scriptName, interpreter);
        }
        if (Strings.isNullOrEmpty(interpreter)) {
            interpreter = this.defaultScriptInterpreter;
            this.logger.debug("[script %s:]: default interpreter: %s", scriptName, interpreter);
        }
        this.logger.info("[script %s]: Using script interpreter: %s", scriptName, interpreter);
        this.logger.debug("[script %s]: %s", scriptName, scriptContent);

        setter.accept(interpreter, scriptContent);
    }

    private String detectInterpreter(final String scriptContent) throws IOException {
        final String firstLine = CharSource.wrap(scriptContent).readFirstLine();
        if (Strings.isNullOrEmpty(firstLine)) {
            return null;
        }

        if (firstLine.startsWith("#!") && firstLine.length() > 2) {
            return firstLine.substring(2);
        }

        return null;
    }

    protected void fillPayload(final RpmBuilder builder) throws MojoFailureException, IOException {
        if (this.entries == null) {
            return;
        }

        final BuilderContext ctx;
        if (this.generateIntermediateDirectories.isEmpty()) {
            ctx = builder.newContext();
        } else {
            ctx = new MissingDirectoryGeneratorInterceptor(builder.newContext(), this.generateIntermediateDirectories);
        }

        this.logger.debug("Building payload:");

        for (final PackageEntry entry : this.entries) {
            if (!entry.getSkip()) {
                try {
                    entry.validate();
                } catch (final IllegalStateException e) {
                    throw new MojoFailureException(e.getMessage());
                }

                fillFromEntry(ctx, entry);
            }
        }
    }

    private void customizeHeader(final RpmBuilder builder) {

        builder.setHeaderCustomizer(rpmTagHeader -> {
            if (this.prefixes != null && !this.prefixes.isEmpty()) {
                this.logger.debug("Building relocatable package: %s", this.prefixes);
                rpmTagHeader.putStringArray(RpmTag.PREFIXES, this.prefixes.toArray(new String[0]));
            }

            if (outputTimestampInstant != null) {
                this.logger.debug("Overriding build time: %s", outputTimestampInstant);
                rpmTagHeader.putInt(RpmTag.BUILDTIME, (int) (outputTimestampInstant.toEpochMilli() / 1000));
            }

            if (changelogs != null && !changelogs.isEmpty()) {
                List<String> authors = changelogs.stream().map(Changelog::getAuthor).collect(Collectors.toList());
                List<String> text = changelogs.stream().map(Changelog::getText).collect(Collectors.toList());
                List<Integer> dates = new ArrayList<>();

                for (Changelog changelog : changelogs) {
                    if (changelog.getDate() != null && !changelog.getDate().isEmpty()) {
                        dates.add((int)OffsetDateTime.parse(changelog.getDate()).toEpochSecond());
                    } else {
                        dates.add((int)changelog.getTimestamp());
                    }
                }
                this.logger.debug("Building changelog with authors %s and dates %s and text %s", authors, dates, text);

                Header.putIntFields(rpmTagHeader, dates, RpmTag.CHANGELOG_TIMESTAMP, Integer::intValue);
                rpmTagHeader.putStringArray(RpmTag.CHANGELOG_AUTHOR, authors.toArray(new String[0]));
                rpmTagHeader.putStringArray(RpmTag.CHANGELOG_TEXT, text.toArray(new String[0]));
            }
        });
    }

    private void fillFromEntry(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("  %s:", entry.getName());

        if (entry.getDirectory() != null && entry.getDirectory()) {
            fillFromEntryDirectory(ctx, entry);
        } else if (entry.getFile() != null) {
            fillFromEntryFile(ctx, entry);
        } else if (entry.getLinkTo() != null) {
            fillFromEntryLinkTo(ctx, entry);
        } else if (entry.getCollect() != null) {
            fillFromEntryCollect(ctx, entry);
        } else if (Boolean.TRUE.equals(entry.getGhost())) {
            fillFromEntryGhost(ctx, entry);
        }
    }

    private void fillFromEntryDirectory(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("    as directory:");
        ctx.addDirectory(entry.getName(), makeProvider(entry, "      - "));
    }

    private void fillFromEntryFile(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("    as file:");
        final Path source = entry.getFile().toPath().toAbsolutePath();
        this.logger.debug("      - source: %s", source);
        ctx.addFile(entry.getName(), source, makeProvider(entry, "      - "));
    }

    private void fillFromEntryGhost(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("    as ghost:");

        ctx.addFile(entry.getName(), ByteBuffer.allocate(0), makeProvider(entry, "      - "));
    }

    private void fillFromEntryLinkTo(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("    as symbolic link:");
        this.logger.debug("      - linkTo: %s", entry.getLinkTo());
        ctx.addSymbolicLink(entry.getName(), entry.getLinkTo(), makeProvider(entry, "      - "));
    }

    private void fillFromEntryCollect(final BuilderContext ctx, final PackageEntry entry) throws IOException {
        this.logger.debug("    as collector:");

        final Collector collector = entry.getCollect();

        this.logger.debug("      - configuration: %s", collector);

        final String padding = "          ";

        final Path from = collector.getFrom().toPath();
        final String targetPrefix = entry.getName().endsWith("/") ? entry.getName() : entry.getName() + "/";

        this.logger.debug("      - files:");

        final MojoFileInformationProvider provider = makeProvider(entry, "            - ");

        final DirectoryScanner scanner = new DirectoryScanner();
        scanner.setBasedir(from.toFile());
        scanner.setCaseSensitive(true);
        scanner.setFollowSymlinks(true);
        scanner.setIncludes(collector.getIncludes());
        scanner.setExcludes(collector.getExcludes());
        scanner.scan();

        if (collector.isDirectories()) {
            String[] includedDirectories = scanner.getIncludedDirectories();
            Arrays.sort(includedDirectories);
            for (final String directory : includedDirectories) {
                final Path dir = from.resolve(directory);
                if (dir.equals(from)) {
                    continue;
                }

                RpmMojo.this.logger.debug("%s%s (dir)", padding, dir);
                final Path relative = from.relativize(dir);
                final String targetName = makeUnix(targetPrefix + relative);
                RpmMojo.this.logger.debug("%s  - target: %s", padding, targetName);
                ctx.addDirectory(targetName, provider);
            }
        }

        String[] includedFiles = scanner.getIncludedFiles();
        Arrays.sort(includedFiles);
        for (final String relative : includedFiles) {
            final Path file = from.resolve(relative);
            final String targetName = makeUnix(targetPrefix + relative);

            if (java.nio.file.Files.isSymbolicLink(file)) {
                RpmMojo.this.logger.debug("%s%s (symlink)", padding, file);
                if (collector.isSymbolicLinks()) {
                    final Path sym = java.nio.file.Files.readSymbolicLink(file);
                    RpmMojo.this.logger.debug("%s%s (symlink)", padding, file);
                    RpmMojo.this.logger.debug("%s  - target: %s", padding, targetName);
                    RpmMojo.this.logger.debug("%s  - linkTo: %s", padding, sym.toString());
                    ctx.addSymbolicLink(targetName, sym.toString(), provider);
                } else {
                    RpmMojo.this.logger.debug("%s%s (symlink) - ignoring symbolic links", padding, file);
                }
            } else {
                RpmMojo.this.logger.debug("%s%s (file)", padding, file);
                RpmMojo.this.logger.debug("%s  - target: %s", padding, targetName);

                ctx.addFile(targetName, file, provider);
            }
        }
    }

    protected String makeUnix(final String path) {
        return path.replace("\\", "/");
    }

    private MojoFileInformationProvider makeProvider(final PackageEntry entry, final String padding) {
        String ruleset = this.defaultRuleset;
        if (entry.getRuleset() != null && !entry.getRuleset().isEmpty()) {
            this.logger.debug("Using specified ruleset: '%s'", entry.getRuleset());
            ruleset = entry.getRuleset();
        } else if (this.defaultRuleset != null && !this.defaultRuleset.isEmpty()) {
            this.logger.debug("Using default ruleset: '%s'", this.defaultRuleset);
        }
        return new MojoFileInformationProvider(this.eval, ruleset, entry, l -> this.logger.debug("%s%s", padding, l), outputTimestampInstant);
    }

    private String makePackageName() {
        final Case nameCase;
        if (this.naming == null) {
            nameCase = Case.UNMODIFIED;
            if (!this.packageName.toLowerCase().equals(this.packageName)) {
                getLog().warn("Since version 0.9.0 of the RPM builder mojo the default behavior of forcing a lower case package name was removed. This package name seems to contain non-lowercase characters. It is possible to restore the previous behavior by setting the 'case' value in the 'naming' element.");
            }
        } else {
            nameCase = this.naming.getCase();
        }

        switch (nameCase) {
            case LOWERCASE:
                return this.packageName.trim().toLowerCase();
            case UNMODIFIED:
            default:
                return this.packageName.trim();
        }
    }

    private RpmVersion makeVersion() {
        if (!this.forceRelease && isSnapshotVersion()) {
            if (this.snapshotVersion != null && !this.snapshotVersion.isEmpty()) {
                this.logger.info("Building with SNAPSHOT version from <snapshotVersion> parameter: %s", this.snapshotVersion);
                return new RpmVersion(this.epoch, this.snapshotVersion, makeSnapshotReleaseString());
            }

            final String baseVersion = this.project.getVersion().substring(0, this.project.getVersion().length() - SNAPSHOT_SUFFIX.length());
            this.logger.info("Building with SNAPSHOT version from project: %s", baseVersion);
            return new RpmVersion(this.epoch, baseVersion, makeSnapshotReleaseString());
        }
        return new RpmVersion(this.epoch, this.version, this.release);
    }

    private boolean isSnapshotVersion() {
        return this.project.getVersion().endsWith(SNAPSHOT_SUFFIX);
    }

    private String makeSnapshotReleaseString() {
        if (this.snapshotBuildId == null || this.snapshotBuildId.isEmpty()) {
            final DateTimeFormatter formatter = DateTimeFormatter.ofPattern("yyyyMMddHHmm", Locale.ROOT);
            return this.snapshotReleasePrefix + formatter.format(Optional.ofNullable(outputTimestampInstant).orElse(Instant.now()).atOffset(ZoneOffset.UTC));
        } else {
            return this.snapshotReleasePrefix + this.snapshotBuildId;
        }
    }

    protected void fillPackageInformation(final RpmBuilder builder) {
        final PackageInformation pinfo = builder.getInformation();

        if (this.sourcePackage == null || this.sourcePackage.isEmpty()) {
            if (this.generateDefaultSourcePackage) {
                final String sourcePackage = generateDefaultSourcePackageName();
                this.logger.debug("Using generated source package name of '%s'. You can disable this by setting 'generateDefaultSourcePackage' to false.", sourcePackage);
                this.sourcePackage = sourcePackage;
            }
        }

        ifSet(pinfo::setDescription, this.description);
        ifSet(pinfo::setSummary, this.summary);
        ifSet(pinfo::setGroup, this.group);
        ifSet(pinfo::setDistribution, this.distribution);
        ifSet(pinfo::setOperatingSystem, this.operatingSystem);
        ifSet(pinfo::setSourcePackage, this.sourcePackage);

        if (this.evalHostname) {
            ifSet(pinfo::setBuildHost, makeHostname());
        }

        ifSet(pinfo::setUrl, this.project.getUrl());
        ifSet(pinfo::setVendor, this.vendor, this::makeVendor);
        ifSet(pinfo::setPackager, this.packager, this::makePackager);

        ifSet(pinfo::setLicense, this.license, this::makeLicense);
    }

    private String generateDefaultSourcePackageName() {
        return RpmLead.toLeadName(makePackageName(), makeVersion()) + ".src.rpm";
    }

    private String makeVendor() {
        if (this.project.getOrganization() != null) {
            return this.project.getOrganization().getName();
        }
        return null;
    }

    private String makePackager() {
        if (this.project.getOrganization() == null) {
            return null;
        }

        final String org = this.project.getOrganization().getName();
        final String url = this.project.getOrganization().getUrl();

        if (org == null || url == null || org.isEmpty() || url.isEmpty()) {
            return null;
        }

        return String.format("%s <%s>", org, url);
    }

    private String makeLicense() {
        return this.project.getLicenses().stream().map(License::getName).collect(Collectors.joining(", "));
    }

    private String makeHostname() {
        String hostname;

        try {
            hostname = readAllLines(Paths.get("/etc/hostname"), US_ASCII).stream().findFirst().orElse(null);

            if (hostname != null && !hostname.isEmpty()) {
                this.logger.debug("Hostname: from /etc/hostname -> '%s'", hostname);
                return hostname;
            }
        } catch (final IOException e) {
            // ignore and try one of the following ways to detect the hostname
        }

        hostname = System.getenv("COMPUTERNAME");
        if (hostname != null && !hostname.isEmpty()) {
            this.logger.debug("Hostname: from COMPUTERNAME -> '%s'", hostname);
            return hostname.toLowerCase();
        }

        hostname = System.getenv("hostname");
        if (hostname != null && !hostname.isEmpty()) {
            this.logger.debug("Hostname: from hostname -> '%s'", hostname);
            return hostname;
        }

        try {
            hostname = InetAddress.getLocalHost().getHostName();
            this.logger.debug("Hostname: from lookup -> '%s'", hostname);
            return hostname;
        } catch (final UnknownHostException e) {
            this.logger.debug("Hostname: Falling back to 'localhost'");
            return "localhost";
        }
    }

    private interface StringSupplier extends Supplier<String> {
    }

    private static void ifSet(final Consumer<String> setter, final String value, final StringSupplier... suppliers) {
        if (value != null && !value.isEmpty()) {
            setter.accept(value);
            return;
        }

        for (final StringSupplier sup : suppliers) {
            final String v = sup.get();
            if (v != null && !v.isEmpty()) {
                setter.accept(v);
                return;
            }
        }
    }


    public static class Changelog extends RpmInformation.Changelog {

        private String date;

        public String getDate() {
            return date;
        }
    }
}
