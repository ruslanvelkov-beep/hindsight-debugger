// SPDX-License-Identifier: GPL-3.0-or-later
package io.hindsight.viewer.source;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Resolves a recorded class/file to its on-disk source lines (VIEWER.md §3), the
 * bridge from a recording's symbol table to the Source window's text.
 *
 * <p>A class's file is looked up under each configured root as
 * {@code <root>/<packagePath>/<fileName>}, first existing wins, where
 * {@code packagePath} comes from the class binary name. Roots come either directly
 * (each a package root, i.e. the directory that contains the top-level package) or
 * from {@code --repo} trees whose {@code src/main/java} and {@code src/test/java}
 * directories are auto-discovered. Both hits and misses are cached so each file is
 * read at most once and a missing source is not re-probed on every cursor move.
 *
 * <p>Lines are kept raw (UTF-8, no tab expansion) so the TUI owns all column math;
 * an unreadable or absent file resolves to {@link Optional#empty()} — a missing
 * source is non-fatal and merely shows a placeholder.
 */
public final class SourcePath {

    private final List<Path> roots;
    private final List<Path> bases;
    private final List<GitSource> gitSources;
    private final Map<String, Optional<List<String>>> cache = new HashMap<>();
    private final Map<String, Optional<Path>> pathCache = new HashMap<>();
    private final Map<Path, Optional<List<String>>> fileCache = new HashMap<>();

    private SourcePath(final List<Path> roots, final List<Path> bases, final List<GitSource> gitSources) {
        this.roots = roots;
        this.bases = bases;
        this.gitSources = gitSources;
    }

    /**
     * Builds a source path from explicit package roots plus repo trees to auto-scan.
     * {@code null} lists are treated as empty.
     */
    public static SourcePath of(final List<Path> roots, final List<Path> repos) {
        return of(roots, repos, "");
    }

    /**
     * Builds a source path pinned to the recording's {@code sourceCommit} where possible
     * (VIEWER.md §26.3): a repo in which the commit resolves is read through {@link GitSource}
     * — its source roots and file contents come from git's tree at that exact commit, so a
     * drifted or differently-branched checkout still shows the code that actually ran. Repos
     * where git/the commit is unavailable, and all explicit package roots, fall back to the
     * working tree exactly as before.
     *
     * @param roots explicit package roots ({@code --source}), always read from disk
     * @param repos repo trees ({@code --repo}); each is git-pinned when possible, else scanned
     * @param sourceCommit the manifest's commit hash, or empty/null for plain working-tree mode
     */
    public static SourcePath of(final List<Path> roots, final List<Path> repos, final String sourceCommit) {
        List<Path> all = new ArrayList<>();
        List<Path> bases = new ArrayList<>();
        List<GitSource> git = new ArrayList<>();
        if (roots != null) {
            all.addAll(roots);
        }
        if (repos != null) {
            for (Path repo : repos) {
                if (!Files.isDirectory(repo)) {
                    continue;
                }
                bases.add(repo.toAbsolutePath().normalize());
                Optional<GitSource> gs = sourceCommit == null || sourceCommit.isEmpty()
                        ? Optional.empty()
                        : GitSource.open(repo, sourceCommit);
                gs.ifPresent(git::add); // §26.3: this repo reads from the commit first
                // The working tree stays discovered either way: for a pinned repo its roots are
                // the FALLBACK for files absent from the commit (e.g. recorded from a dirty
                // checkout); for an unpinned repo they are the only source, as before.
                all.addAll(discover(repo));
            }
        }
        return new SourcePath(List.copyOf(all), List.copyOf(bases), List.copyOf(git));
    }

    /** The §26.3 commit-pinned repo views (empty in plain working-tree mode). */
    public List<GitSource> gitSources() {
        return gitSources;
    }

    /**
     * The resolved package roots, in resolution order (the explicit {@code --source} roots
     * first, then each {@code --repo}'s auto-discovered {@code src/main/java} /
     * {@code src/test/java} directories). Immutable. The open-file browser walks these to
     * build its directory tree (VIEWER.md §17.7).
     */
    public List<Path> roots() {
        return roots;
    }

    /**
     * The project roots (§24.6): each {@code --repo} dir, absolute and normalised — the tops
     * the open-file browser shows its trees from, so every directory between a repo root and
     * its package roots is visible. Explicit {@code --source} roots have no base and keep
     * appearing as their own top-level trees.
     */
    public List<Path> bases() {
        return bases;
    }

    /**
     * The source lines of {@code fileName} for {@code classBinary}, or empty when no root
     * resolves it (or the file is unreadable). Result is cached, so repeat calls are free.
     */
    public Optional<List<String>> lines(final String classBinary, final String fileName) {
        String key = classBinary + " " + fileName;
        Optional<List<String>> cached = cache.get(key);
        if (cached != null) {
            return cached;
        }
        Optional<List<String>> result = load(classBinary, fileName);
        cache.put(key, result);
        return result;
    }

    private Optional<List<String>> load(final String classBinary, final String fileName) {
        // §26.3: the commit-pinned repos win on EVERY access path (lines, resolveClass,
        // linesOfFile, coverageKey) so one class always serves ONE content — they also resolve
        // classes whose source root does not even exist in the current checkout (e.g. a module
        // migrated on another branch). The working tree is the fallback, covering explicit
        // --source roots and files absent from the pinned commit.
        String key = packageRelativeKey(classBinary, fileName);
        if (key != null) {
            for (GitSource g : gitSources) {
                Optional<List<String>> got = g.lines(key);
                if (got.isPresent()) {
                    return got;
                }
            }
        }
        return resolvePath(classBinary, fileName).flatMap(this::readLines);
    }

    /** {@code com.foo.Bar} + {@code Bar.java} → {@code com/foo/Bar.java}; null without a file name. */
    private static String packageRelativeKey(final String classBinary, final String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return null;
        }
        String pkg = packagePath(classBinary);
        return pkg.isEmpty() ? fileName : pkg + "/" + fileName;
    }

    /**
     * The on-disk path of {@code fileName} for {@code classBinary}, or empty when no root resolves
     * it (§11.8). Same resolution as {@link #lines}, but yields the {@link Path} the "open a source
     * file" dialog needs to enter browse mode. The result (hit or miss) is cached.
     */
    public Optional<Path> resolveClass(final String classBinary, final String fileName) {
        String key = classBinary + " " + fileName;
        Optional<Path> cached = pathCache.get(key);
        if (cached != null) {
            return cached;
        }
        // §26.3: pinned commits first (mirroring load()) — the resolved path may be VIRTUAL
        // (<repo>/<relpath> absent from disk); linesOfFile routes such paths back through git.
        Optional<Path> result = Optional.empty();
        String rel = packageRelativeKey(classBinary, fileName);
        if (rel != null) {
            for (GitSource g : gitSources) {
                Optional<Path> p = g.resolve(rel);
                if (p.isPresent()) {
                    result = p;
                    break;
                }
            }
        }
        if (result.isEmpty()) {
            result = resolvePath(classBinary, fileName);
        }
        pathCache.put(key, result);
        return result;
    }

    /**
     * The UTF-8 lines of an already-known file (browse mode, §11.8), or empty when the path is not
     * a readable regular file. Unlike {@link #lines}, this reads a caller-supplied path directly
     * rather than resolving a class; the result (hit or miss) is cached per normalised path.
     */
    public Optional<List<String>> linesOfFile(final Path file) {
        if (file == null) {
            return Optional.empty();
        }
        Path key = file.toAbsolutePath().normalize();
        Optional<List<String>> cached = fileCache.get(key);
        if (cached != null) {
            return cached;
        }
        // §26.3: paths inside a commit-pinned repo read from git FIRST — even when the working
        // tree has a same-named file, its content may belong to a different branch.
        Optional<List<String>> result = Optional.empty();
        for (GitSource g : gitSources) {
            result = g.linesOfFile(key);
            if (result.isPresent()) {
                break;
            }
        }
        if (result.isEmpty()) {
            result = readLines(key);
        }
        fileCache.put(key, result);
        return result;
    }

    /**
     * §28.1: the shown file's display path — from the repo ({@code .git}) root when the file lies
     * in a commit-pinned or {@code --repo} tree, else the source-root-relative key, else the bare
     * file name. Always {@code /}-joined. Empty only for a {@code null} file.
     */
    public String displayPath(final Path file) {
        if (file == null) {
            return "";
        }
        for (GitSource g : gitSources) {
            Optional<String> rel = g.relPathOf(file);
            if (rel.isPresent()) {
                return rel.get();
            }
        }
        Path abs = file.toAbsolutePath().normalize();
        Path owner = null;
        for (Path base : bases) {
            if (abs.startsWith(base) && (owner == null || base.getNameCount() > owner.getNameCount())) {
                owner = base;
            }
        }
        if (owner != null) {
            return joinSlash(owner.relativize(abs));
        }
        return coverageKey(file).orElseGet(
                () -> abs.getFileName() != null ? abs.getFileName().toString() : abs.toString());
    }

    /** A relative path {@code /}-joined regardless of platform separators. */
    private static String joinSlash(final Path rel) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i));
        }
        return sb.toString();
    }

    /**
     * The FORMAT.md §12.1 package-relative coverage key of an on-disk or §26.3 virtual file —
     * from the owning commit-pinned repo when the path is git-indexed, else the path relative to
     * the deepest containing working-tree source root. Empty when the file lies outside both.
     */
    public Optional<String> coverageKey(final Path file) {
        if (file == null) {
            return Optional.empty();
        }
        for (GitSource g : gitSources) {
            Optional<String> k = g.keyOf(file);
            if (k.isPresent()) {
                return k;
            }
        }
        Path abs = file.toAbsolutePath().normalize();
        Path owner = null;
        for (Path root : roots) {
            Path r = root.toAbsolutePath().normalize();
            if (abs.startsWith(r) && (owner == null || r.getNameCount() > owner.getNameCount())) {
                owner = r;
            }
        }
        if (owner == null) {
            return Optional.empty();
        }
        Path rel = owner.relativize(abs);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < rel.getNameCount(); i++) {
            if (i > 0) {
                sb.append('/');
            }
            sb.append(rel.getName(i));
        }
        return Optional.of(sb.toString());
    }

    /**
     * Best-effort owning source-project directory for {@code classBinary}: the path segment
     * immediately preceding {@code src/main/java} or {@code src/test/java} in the resolved file's
     * path (e.g. {@code hedera-app}), or empty when the class does not resolve or its path carries
     * no such marker (§11.5/§11.8).
     */
    public Optional<String> module(final String classBinary, final String fileName) {
        Optional<Path> resolved = resolveClass(classBinary, fileName);
        if (resolved.isEmpty()) {
            return Optional.empty();
        }
        Path path = resolved.get().toAbsolutePath().normalize();
        int n = path.getNameCount();
        for (int i = 1; i + 2 < n; i++) {
            if (!path.getName(i).toString().equals("src")) {
                continue;
            }
            String mid = path.getName(i + 1).toString();
            String java = path.getName(i + 2).toString();
            if ((mid.equals("main") || mid.equals("test")) && java.equals("java")) {
                return Optional.of(path.getName(i - 1).toString());
            }
        }
        return Optional.empty();
    }

    private Optional<Path> resolvePath(final String classBinary, final String fileName) {
        if (fileName == null || fileName.isEmpty()) {
            return Optional.empty();
        }
        String pkgPath = packagePath(classBinary);
        for (Path root : roots) {
            Path candidate = pkgPath.isEmpty() ? root.resolve(fileName) : root.resolve(pkgPath).resolve(fileName);
            if (Files.isRegularFile(candidate)) {
                return Optional.of(candidate);
            }
        }
        return Optional.empty();
    }

    private Optional<List<String>> readLines(final Path file) {
        if (!Files.isRegularFile(file)) {
            return Optional.empty();
        }
        try {
            return Optional.of(List.copyOf(Files.readAllLines(file, StandardCharsets.UTF_8)));
        } catch (IOException e) {
            // Unreadable (permissions, bad encoding): treat as a miss.
            return Optional.empty();
        }
    }

    /** {@code com.foo.Bar} → {@code com/foo}; empty for the default package or a {@code null} class. */
    static String packagePath(final String classBinary) {
        if (classBinary == null) {
            return "";
        }
        int lastDot = classBinary.lastIndexOf('.');
        return lastDot < 0 ? "" : classBinary.substring(0, lastDot).replace('.', '/');
    }

    private static List<Path> discover(final Path repo) {
        List<Path> found = new ArrayList<>();
        if (!Files.isDirectory(repo)) {
            return found;
        }
        try {
            Files.walkFileTree(repo, new SimpleFileVisitor<Path>() {
                @Override
                public FileVisitResult preVisitDirectory(final Path dir, final BasicFileAttributes attrs) {
                    Path nameElem = dir.getFileName();
                    String name = nameElem != null ? nameElem.toString() : "";
                    if (name.equals("build") || name.equals(".git") || name.equals("node_modules")) {
                        return FileVisitResult.SKIP_SUBTREE;
                    }
                    String norm = dir.toString().replace('\\', '/');
                    if (norm.endsWith("src/main/java") || norm.endsWith("src/test/java")) {
                        found.add(dir);
                    }
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException e) {
            // Partial discovery is acceptable — a repo we cannot fully walk still yields what we found.
        }
        return found;
    }
}
