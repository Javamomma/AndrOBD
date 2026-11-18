package com.fr3ts0n.ecu.gui.androbd;

import org.junit.Test;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.xml.parsers.DocumentBuilderFactory;

import static org.junit.Assert.assertTrue;

/**
 * Guards the fold fix (FOLD.md). Folding or unfolding a Galaxy Fold changes the screen size.
 * An activity that doesn't declare those changes in configChanges is destroyed and rebuilt,
 * and MainActivity.onDestroy() stops the adapter link (and can switch Bluetooth off).
 */
public class FoldSafetyTest
{
    private static final String ANDROID_NS = "http://schemas.android.com/apk/res/android";

    /** What a fold or unfold changes; every activity handles these itself. */
    private static final List<String> FOLD_CHANGES =
        Arrays.asList("orientation", "screenSize", "smallestScreenSize", "screenLayout");

    /** Activities that also stay alive through a density or day/night change. */
    private static final Map<String, List<String>> EXTRA_CHANGES = new HashMap<>();
    static
    {
        EXTRA_CHANGES.put(".MainActivity", Arrays.asList("density", "uiMode"));
        EXTRA_CHANGES.put(".fold.FoldDashboardActivity", Arrays.asList("density", "uiMode"));
    }

    @Test
    public void everyActivityHandlesFoldItself() throws Exception
    {
        Element manifest = DocumentBuilderFactory.newInstance().newDocumentBuilder()
            .parse(source("src/main/AndroidManifest.xml")).getDocumentElement();
        // DocumentBuilderFactory is not namespace aware by default, so read the prefixed names
        NodeList activities = manifest.getElementsByTagName("activity");
        List<String> problems = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < activities.getLength(); i++)
        {
            Element activity = (Element) activities.item(i);
            String name = activity.getAttribute("android:name");
            seen.add(name);
            Set<String> declared = new HashSet<>(
                Arrays.asList(activity.getAttribute("android:configChanges").split("\\|")));
            List<String> required = new ArrayList<>(FOLD_CHANGES);
            if (EXTRA_CHANGES.containsKey(name)) required.addAll(EXTRA_CHANGES.get(name));
            List<String> missing = new ArrayList<>();
            for (String change : required) if (!declared.contains(change)) missing.add(change);
            if (!missing.isEmpty()) problems.add(name + " is missing configChanges " + missing);
        }
        assertTrue("manifest not parsed: no .MainActivity among " + seen, seen.contains(".MainActivity"));
        assertTrue("Activities rebuilt on fold/unfold:\n" + String.join("\n", problems), problems.isEmpty());
    }

    @Test
    public void mainActivityRebuildsOnlyThroughRecreateWhenSafe() throws Exception
    {
        String code = withoutComments(new String(Files.readAllBytes(
            source("src/main/java/com/fr3ts0n/ecu/gui/androbd/MainActivity.java").toPath()),
            StandardCharsets.UTF_8));
        int decl = code.indexOf("void recreateWhenSafe(");
        assertTrue("MainActivity.recreateWhenSafe() not found", decl >= 0);
        int[] body = braceBlock(code, code.indexOf('{', decl));

        List<String> outside = new ArrayList<>();
        int inside = 0;
        Matcher m = Pattern.compile("\\brecreate\\s*\\(\\s*\\)").matcher(code);
        while (m.find())
        {
            if (m.start() > body[0] && m.start() < body[1]) inside++;
            else outside.add("line " + lineOf(code, m.start()));
        }
        assertTrue("recreate() called outside recreateWhenSafe() at " + outside
            + ". Rebuilding MainActivity while a link is up stops the adapter; call recreateWhenSafe().",
            outside.isEmpty());
        assertTrue("recreateWhenSafe() no longer calls recreate()", inside > 0);
    }

    /** Unit tests run from the module directory; fall back to the repo root. */
    private static File source(String path)
    {
        File f = new File(path);
        return f.exists() ? f : new File("androbd", path);
    }

    /** Blank out comments but keep newlines, so offsets still map to line numbers. */
    private static String withoutComments(String code)
    {
        Matcher m = Pattern.compile("/\\*.*?\\*/|//[^\\n]*", Pattern.DOTALL).matcher(code);
        StringBuilder out = new StringBuilder();
        int last = 0;
        while (m.find())
        {
            out.append(code, last, m.start()).append(m.group().replaceAll("[^\\n]", " "));
            last = m.end();
        }
        return out.append(code.substring(last)).toString();
    }

    /** [open, close] offsets of the brace block starting at {@code open}. */
    private static int[] braceBlock(String code, int open)
    {
        int depth = 0;
        for (int i = open; i < code.length(); i++)
        {
            char c = code.charAt(i);
            if (c == '{') depth++;
            else if (c == '}' && --depth == 0) return new int[]{open, i};
        }
        throw new AssertionError("unbalanced braces after offset " + open);
    }

    private static int lineOf(String code, int offset)
    {
        int line = 1;
        for (int i = 0; i < offset; i++) if (code.charAt(i) == '\n') line++;
        return line;
    }
}
