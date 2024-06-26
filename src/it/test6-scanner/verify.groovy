import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

final Logger logger = LoggerFactory.getLogger("test6-scanner verify");

Process rpm = ("rpm -qp --fileclass " + basedir.toString().replace(File.separator, "/") + "/target/test6.rpm").execute();

def entries = rpm.in.readLines();

def trimmedEntries = entries.collect { it.trim().replaceAll("\\s+", " ") };

logger.info (trimmedEntries.join(", "));

def actual = trimmedEntries
def expected = [
        "/usr/share/test6/a.foo",
        "/usr/share/test6/include directory",
        "/usr/share/test6/include/d.bar",
        "/usr/share/test6/link_to_a.foo symbolic link to `a.foo'",
]

if (actual != expected) {
    System.out.format("RPM file entries doesn't match - actual:%n%s%nexpected:%n%s%n", actual, expected);
    return false;
}
