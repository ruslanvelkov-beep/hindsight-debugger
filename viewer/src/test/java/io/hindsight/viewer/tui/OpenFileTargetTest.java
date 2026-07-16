// SPDX-License-Identifier: Apache-2.0
package io.hindsight.viewer.tui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests the open-file target grammar of VIEWER.md §11.8 (dotted class / path, optional line). */
final class OpenFileTargetTest {

    @Test
    void blankIsNull() {
        assertNull(OpenFileTarget.parse(null));
        assertNull(OpenFileTarget.parse(""));
        assertNull(OpenFileTarget.parse("   "));
    }

    @Test
    void dottedClassMapsToFileName() {
        OpenFileTarget.Target t = OpenFileTarget.parse("com.foo.Bar");
        assertFalse(t.path());
        assertEquals("com.foo.Bar", t.classBinary());
        assertEquals("Bar.java", t.fileName());
        assertNull(t.rawPath());
        assertEquals(-1, t.line());
    }

    @Test
    void dottedClassWithLine() {
        OpenFileTarget.Target t = OpenFileTarget.parse("com.foo.Bar:42");
        assertFalse(t.path());
        assertEquals("com.foo.Bar", t.classBinary());
        assertEquals("Bar.java", t.fileName());
        assertEquals(42, t.line());
    }

    @Test
    void simpleClassNameDefaultPackage() {
        OpenFileTarget.Target t = OpenFileTarget.parse("Bar");
        assertFalse(t.path());
        assertEquals("Bar", t.classBinary());
        assertEquals("Bar.java", t.fileName());
    }

    @Test
    void relativePathTreatedAsPath() {
        OpenFileTarget.Target t = OpenFileTarget.parse("com/foo/Bar.java");
        assertTrue(t.path());
        assertNull(t.classBinary());
        assertEquals("Bar.java", t.fileName());
        assertEquals("com/foo/Bar.java", t.rawPath());
        assertEquals(-1, t.line());
    }

    @Test
    void relativePathWithLine() {
        OpenFileTarget.Target t = OpenFileTarget.parse("src/main/java/com/foo/Bar.java:10");
        assertTrue(t.path());
        assertEquals("Bar.java", t.fileName());
        assertEquals("src/main/java/com/foo/Bar.java", t.rawPath());
        assertEquals(10, t.line());
    }

    @Test
    void bareJavaFileIsPath() {
        OpenFileTarget.Target t = OpenFileTarget.parse("Bar.java");
        assertTrue(t.path());
        assertEquals("Bar.java", t.fileName());
        assertEquals("Bar.java", t.rawPath());
    }

    @Test
    void pathWithoutJavaSuffixButWithSlash() {
        OpenFileTarget.Target t = OpenFileTarget.parse("foo/Baz");
        assertTrue(t.path());
        assertEquals("Baz", t.fileName());
    }

    @Test
    void windowsDriveColonNotTakenAsLine() {
        OpenFileTarget.Target t = OpenFileTarget.parse("C:/proj/Bar.java");
        assertTrue(t.path());
        assertEquals("C:/proj/Bar.java", t.rawPath());
        assertEquals("Bar.java", t.fileName());
        assertEquals(-1, t.line());
    }

    @Test
    void windowsDrivePathWithTrailingLine() {
        OpenFileTarget.Target t = OpenFileTarget.parse("C:/proj/Bar.java:20");
        assertTrue(t.path());
        assertEquals("C:/proj/Bar.java", t.rawPath());
        assertEquals(20, t.line());
    }
}
