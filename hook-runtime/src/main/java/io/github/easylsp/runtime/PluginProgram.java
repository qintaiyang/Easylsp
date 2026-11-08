package io.github.easylsp.runtime;

import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.zip.*;

/** Immutable source snapshot shared by online, startup and standalone loading. */
public final class PluginProgram {
    public static final String FORMAT = "program-v1";
    public static final class Component {
        public final String id, path;
        public final List<String> requires;
        Component(JSONObject value) throws JSONException {
            id = value.getString("id"); path = value.getString("path");
            if (!id.matches("[A-Za-z][A-Za-z0-9_.-]*")) throw new IllegalArgumentException("Invalid component id: " + id);
            requires = strings(value.optJSONArray("requires"));
        }
    }
    public final Map<String, String> files;
    public final List<Component> components;
    public final Map<String, Integer> provides, requires;
    public PluginProgram(Map<String, String> source) throws JSONException {
        files = Collections.unmodifiableMap(new LinkedHashMap<>(source));
        if (!files.containsKey("main.java")) throw new IllegalArgumentException("Missing main.java");
        JSONObject manifest = files.containsKey("plugin.json") ? new JSONObject(files.get("plugin.json")) : new JSONObject().put("schema", 1);
        if (manifest.getInt("schema") != 1) throw new IllegalArgumentException("Unsupported plugin.json schema");
        Map<String, Component> declared = new LinkedHashMap<>();
        Set<String> paths = new HashSet<>();
        JSONArray array = manifest.optJSONArray("components");
        if (array != null) for (int i = 0; i < array.length(); i++) {
            Component c = new Component(array.getJSONObject(i));
            if (!c.path.startsWith("src/") || !c.path.endsWith(".bsh") || !files.containsKey(c.path)) throw new IllegalArgumentException("Missing component source: " + c.path);
            if (declared.put(c.id, c) != null || !paths.add(c.path)) throw new IllegalArgumentException("Duplicate component: " + c.id);
        }
        List<Component> ordered = new ArrayList<>();
        Set<String> visiting = new LinkedHashSet<>(), done = new HashSet<>();
        for (String id : declared.keySet()) visit(id, declared, visiting, done, ordered);
        components = Collections.unmodifiableList(ordered);
        JSONObject services = manifest.optJSONObject("services");
        provides = versions(services == null ? null : services.optJSONObject("provides"));
        requires = versions(services == null ? null : services.optJSONObject("requires"));
        for (String name : requires.keySet()) if (provides.containsKey(name)) throw new IllegalArgumentException("Service depends on itself: " + name);
    }
    private static List<String> strings(JSONArray a) throws JSONException {
        List<String> result = new ArrayList<>();
        if (a != null) for (int i = 0; i < a.length(); i++) result.add(a.getString(i));
        return Collections.unmodifiableList(result);
    }
    private static Map<String, Integer> versions(JSONObject value) throws JSONException {
        Map<String, Integer> result = new LinkedHashMap<>();
        if (value != null) for (Iterator<String> keys = value.keys(); keys.hasNext();) {
            String name = keys.next(); Object version = value.get(name);
            if (!name.matches("[A-Za-z][A-Za-z0-9_.-]*") || !(version instanceof Number) || ((Number)version).doubleValue() != ((Number)version).intValue() || ((Number)version).intValue() < 1)
                throw new IllegalArgumentException("Invalid service version: " + name);
            result.put(name, ((Number)version).intValue());
        }
        return Collections.unmodifiableMap(result);
    }
    private static void visit(String id, Map<String, Component> all, Set<String> visiting, Set<String> done, List<Component> result) {
        if (done.contains(id)) return;
        Component c = all.get(id);
        if (c == null) throw new IllegalArgumentException("Missing component dependency: " + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("Component cycle: " + visiting + " -> " + id);
        for (String dependency : c.requires) visit(dependency, all, visiting, done, result);
        visiting.remove(id); done.add(id); result.add(c);
    }
    public void validate() throws Exception {
        List<String> scripts = new ArrayList<>(); scripts.add("main.java");
        for (Component c : components) scripts.add(c.path);
        for (String path : scripts) try { PluginEngine.readAndParse(new StringReader(files.get(path))); }
        catch (bsh.ParseException | bsh.TokenMgrError error) { throw new IllegalArgumentException(path + ": " + error.getMessage(), error); }
    }
    public String text(String path) {
        String value = files.get(path);
        if (value == null) throw new IllegalArgumentException("Missing program file: " + path);
        return value;
    }
    public static PluginProgram single(Reader reader) throws Exception {
        StringBuilder s = new StringBuilder(); char[] buffer = new char[4096]; int n;
        while ((n = reader.read(buffer)) != -1) s.append(buffer, 0, n);
        return new PluginProgram(Collections.singletonMap("main.java", s.toString()));
    }
    public void write(OutputStream output) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(output)) {
            for (Map.Entry<String, String> file : files.entrySet()) {
                zip.putNextEntry(new ZipEntry(file.getKey())); zip.write(file.getValue().getBytes(StandardCharsets.UTF_8)); zip.closeEntry();
            }
        }
    }
    public static PluginProgram read(InputStream input) throws Exception {
        Map<String, String> files = new LinkedHashMap<>(); int total = 0;
        try (ZipInputStream zip = new ZipInputStream(input)) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                String path = entry.getName();
                if (path.startsWith("/") || path.indexOf(92) >= 0 || Arrays.stream(path.split("/", -1)).anyMatch(p -> p.isEmpty() || p.equals(".") || p.equals(".."))) throw new IllegalArgumentException("Invalid program path: " + path);
                if (files.containsKey(path) || files.size() >= 200) throw new IllegalArgumentException("Duplicate or excessive program files");
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n;
                while ((n = zip.read(buffer)) != -1) {
                    total += n;
                    if (bytes.size() + n > 1048576 || total > 32 * 1048576) throw new IllegalArgumentException("Program size limit exceeded");
                    bytes.write(buffer, 0, n);
                }
                files.put(path, StandardCharsets.UTF_8.newDecoder().decode(java.nio.ByteBuffer.wrap(bytes.toByteArray())).toString());
            }
        }
        return new PluginProgram(files);
    }
    public static List<String> order(Map<String, PluginProgram> programs) {
        return order(programs, Collections.emptyMap());
    }
    public static List<String> order(Map<String, PluginProgram> programs, Map<String, List<String>> dependencies) {
        Map<String, String> owners = new HashMap<>();
        for (Map.Entry<String, PluginProgram> p : programs.entrySet()) for (String name : p.getValue().provides.keySet())
            if (owners.put(name, p.getKey()) != null) throw new IllegalArgumentException("Duplicate service provider: " + name);
        List<String> result = new ArrayList<>(); Set<String> visiting = new LinkedHashSet<>();
        for (String id : programs.keySet()) orderVisit(id, programs, owners, dependencies, visiting, result);
        return result;
    }
    private static void orderVisit(String id, Map<String, PluginProgram> programs, Map<String, String> owners, Map<String, List<String>> dependencies, Set<String> visiting, List<String> result) {
        if (result.contains(id)) return;
        if (!visiting.add(id)) throw new IllegalArgumentException("Service dependency cycle: " + visiting + " -> " + id);
        for (String dependency : dependencies.getOrDefault(id, Collections.emptyList())) {
            if (!programs.containsKey(dependency)) throw new IllegalArgumentException("Missing plugin dependency: " + dependency);
            orderVisit(dependency, programs, owners, dependencies, visiting, result);
        }
        for (Map.Entry<String, Integer> need : programs.get(id).requires.entrySet()) {
            String owner = owners.get(need.getKey());
            if (owner == null || !need.getValue().equals(programs.get(owner).provides.get(need.getKey()))) throw new IllegalArgumentException("Missing service/version: " + need);
            orderVisit(owner, programs, owners, dependencies, visiting, result);
        }
        visiting.remove(id); result.add(id);
    }
}
