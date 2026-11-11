package eu.glazelab.api.config;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.boot.info.BuildProperties;

import static org.junit.jupiter.api.Assertions.*;

class SourceCodeTest {
    private static ObjectProvider<BuildProperties> build(String... pairs) {
        var factory = new StaticListableBeanFactory();
        if (pairs.length > 0) {
            var properties = new Properties();
            for (int i = 0; i < pairs.length; i += 2) properties.setProperty(pairs[i], pairs[i + 1]);
            factory.addBean("buildProperties", new BuildProperties(properties));
        }
        return factory.getBeanProvider(BuildProperties.class);
    }

    @Test void anUnsetOrBlankUrlHidesTheLink() {
        assertNull(new SourceCode("", build()).url());
        assertNull(new SourceCode("   ", build()).url());
    }

    @Test void onlyAnAbsoluteHttpUrlIsAccepted() {
        assertEquals("https://code.example.test/x", new SourceCode(" https://code.example.test/x ", build()).url());
        assertEquals("http://code.example.test", new SourceCode("http://code.example.test", build()).url());
        for (String bad : new String[] {"javascript:alert(1)", "code.example.test/x", "/relative", "ftp://code.example.test", "https://", "https://exa mple.test"}) {
            var error = assertThrows(IllegalStateException.class, () -> new SourceCode(bad, build()), bad);
            assertFalse(error.getMessage().contains(bad), "the message never echoes the value");
        }
    }

    @Test void theBuildIsTheLabelElseTheVersionElseUnknown() {
        assertEquals("abc1234", new SourceCode("", build("version", "0.1.0", "label", "abc1234")).build());
        assertEquals("0.1.0", new SourceCode("", build("version", "0.1.0", "label", " ")).build());
        assertEquals("0.1.0", new SourceCode("", build("version", "0.1.0")).build());
        assertEquals("unknown", new SourceCode("", build()).build());
    }
}
