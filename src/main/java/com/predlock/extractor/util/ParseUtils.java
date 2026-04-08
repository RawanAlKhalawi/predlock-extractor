package com.predlock.extractor.util;

import com.github.javaparser.ParseProblemException;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

public final class ParseUtils {

    private static final Logger log = LoggerFactory.getLogger(ParseUtils.class);

    private static final Set<String> SKIP_DIRS = Set.of(
        "target", "build", "out",
        "generated-sources", "generated-test-sources", "apt-generated",
        "test", "test-classes", "it",
        "node_modules", ".git",
        "resources", "webapp", "static", "public",
        "META-INF", "WEB-INF"
    );

    private ParseUtils() {}

    public static boolean shouldSkip(File dir) {
        return SKIP_DIRS.contains(dir.getName());
    }

    public static List<CompilationUnit> parseFromDir(String dirPath) {
        List<CompilationUnit> results = new ArrayList<>();
        parseFromDir(dirPath, results);
        return results;
    }

    private static void parseFromDir(String dirPath, List<CompilationUnit> results) {
        File[] files = new File(dirPath).listFiles();
        if (files == null) return;
        for (File file : files) {
            if (file.isDirectory()) {
                if (!shouldSkip(file)) {
                    parseFromDir(file.getAbsolutePath(), results);
                }
            } else if (file.getName().toLowerCase().endsWith(".java")) {
                try {
                    results.add(StaticJavaParser.parse(file));
                } catch (ParseProblemException e) {
                    log.warn("Parse error in {}: {}", file.getPath(), e.getMessage());
                } catch (Exception e) {
                    log.warn("Error reading {}: {}", file.getPath(), e.getMessage());
                }
            }
        }
    }
}