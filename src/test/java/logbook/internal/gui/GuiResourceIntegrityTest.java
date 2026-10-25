package logbook.internal.gui;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.util.HashSet;
import java.util.Set;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

/**
 * Regressions for two resource-only startup warnings discovered during
 * Linux Mint 22.1 / JDK 25 isolated GUI testing.
 */
class GuiResourceIntegrityTest {

    private static Document bundledXml(String name) throws Exception {
        try (InputStream in = GuiResourceIntegrityTest.class.getClassLoader()
                .getResourceAsStream(name)) {
            assertNotNull(in, "Missing resource: " + name);
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            return f.newDocumentBuilder().parse(in);
        }
    }

    @Test
    void probabilityCalculatorStylesheetExistsInDistributionResources() throws Exception {
        Element root = bundledXml("logbook/gui/prob_calc.fxml").getDocumentElement();
        String stylesheet = root.getAttribute("stylesheets");
        assertTrue(stylesheet.startsWith("@"), "Expected a relative JavaFX stylesheet reference");
        String path = "logbook/gui/" + stylesheet.substring(1);
        assertNotNull(getClass().getClassLoader().getResource(path),
                "The JavaFX stylesheet used by the calculator must be packaged: " + path);
        assertFalse(stylesheet.endsWith("default.css"),
                "This app does not ship a default.css; use an existing stylesheet");
    }

    @Test
    void everyLog4jAppenderRefHasAConfiguredTarget() throws Exception {
        Document document = bundledXml("log4j2.xml");
        NodeList appenders = document.getElementsByTagName("Appenders");
        assertTrue(appenders.getLength() > 0);
        Set<String> defined = new HashSet<>();
        var children = appenders.item(0).getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element appender) {
                defined.add(appender.getAttribute("name"));
            }
        }
        assertTrue(defined.contains("Console"));
        NodeList refs = document.getElementsByTagName("AppenderRef");
        assertTrue(refs.getLength() > 0);
        for (int i = 0; i < refs.getLength(); i++) {
            String ref = ((Element) refs.item(i)).getAttribute("ref");
            assertTrue(defined.contains(ref), "Undefined Log4j AppenderRef: " + ref);
        }
    }
}
