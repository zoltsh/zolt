package sh.zolt.arch;

import com.sun.source.tree.ClassTree;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.ImportTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.Scope;
import com.sun.source.tree.Tree;
import com.sun.source.util.JavacTask;
import com.sun.source.util.TreePath;
import com.sun.source.util.TreePathScanner;
import com.sun.source.util.Trees;
import java.io.IOException;
import java.net.URI;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import javax.lang.model.element.Element;
import javax.lang.model.element.ElementKind;
import javax.lang.model.element.Modifier;
import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.SimpleJavaFileObject;
import javax.tools.ToolProvider;

/** Java-parser-backed qualified-reference extraction for source guardrails. */
final class JavaSourceReferences {
    private JavaSourceReferences() {
    }

    static Analysis analyze(String source) {
        ParsedUnit parsed = parse(source);
        CompilationUnitTree unit = parsed.unit();
        Set<String> importedSimpleNames = new HashSet<>();
        List<Reference> references = new ArrayList<>();
        for (ImportTree imported : unit.getImports()) {
            String name = compact(imported.getQualifiedIdentifier().toString());
            boolean wildcard = name.endsWith(".*");
            if (wildcard) {
                name = name.substring(0, name.length() - 2);
            } else {
                importedSimpleNames.add(simpleName(name));
            }
            references.add(new Reference(name, parsed.line(imported), wildcard));
        }

        QualifiedReferenceScanner scanner = new QualifiedReferenceScanner(
                parsed,
                references,
                importedSimpleNames);
        scanner.scan(unit, null);

        boolean publicTopLevelType = unit.getTypeDecls().stream()
                .filter(ClassTree.class::isInstance)
                .map(ClassTree.class::cast)
                .anyMatch(type -> type.getModifiers().getFlags().contains(Modifier.PUBLIC));
        Optional<String> packageName = Optional.ofNullable(unit.getPackageName())
                .map(Object::toString)
                .map(JavaSourceReferences::compact);
        return new Analysis(
                packageName,
                List.copyOf(references),
                publicTopLevelType);
    }

    static List<Reference> references(String source) {
        return analyze(source).references();
    }

    static Optional<String> packageName(String source) {
        return analyze(source).packageName();
    }

    static boolean declaresPublicTopLevelType(String source) {
        return analyze(source).publicTopLevelType();
    }

    private static ParsedUnit parse(String source) {
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        if (compiler == null) {
            throw new IllegalStateException("Architecture tests require a JDK compiler");
        }
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        JavaFileObject input = new StringSource(source);
        JavacTask task = (JavacTask) compiler.getTask(
                null,
                null,
                diagnostics,
                List.of("-proc:none"),
                null,
                List.of(input));
        CompilationUnitTree unit;
        try {
            unit = task.parse().iterator().next();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not parse Java source", exception);
        }
        Optional<Diagnostic<? extends JavaFileObject>> error = diagnostics.getDiagnostics().stream()
                .filter(diagnostic -> diagnostic.getKind() == Diagnostic.Kind.ERROR)
                .findFirst();
        if (error.isPresent()) {
            Diagnostic<? extends JavaFileObject> diagnostic = error.orElseThrow();
            throw new IllegalArgumentException(
                    "Could not parse Java source at line " + diagnostic.getLineNumber()
                            + ": " + diagnostic.getMessage(null));
        }
        Trees trees = Trees.instance(task);
        try {
            task.analyze();
        } catch (IOException exception) {
            throw new IllegalArgumentException("Could not attribute Java source", exception);
        }
        return new ParsedUnit(unit, trees);
    }

    private static String compact(String value) {
        StringBuilder compact = new StringBuilder(value.length());
        value.codePoints()
                .filter(character -> !Character.isWhitespace(character))
                .forEach(compact::appendCodePoint);
        return compact.toString();
    }

    private static String simpleName(String name) {
        int separator = name.lastIndexOf('.');
        return separator < 0 ? name : name.substring(separator + 1);
    }

    record Analysis(
            Optional<String> packageName,
            List<Reference> references,
            boolean publicTopLevelType) {
    }

    record Reference(String name, int line, boolean wildcard) {
    }

    private record ParsedUnit(CompilationUnitTree unit, Trees trees) {
        int line(Tree tree) {
            long position = trees.getSourcePositions().getStartPosition(unit, tree);
            return position < 0 ? 1 : Math.toIntExact(unit.getLineMap().getLineNumber(position));
        }
    }

    private static final class QualifiedReferenceScanner extends TreePathScanner<Void, Void> {
        private final ParsedUnit parsed;
        private final List<Reference> references;
        private final Set<String> importedSimpleNames;

        QualifiedReferenceScanner(
                ParsedUnit parsed,
                List<Reference> references,
                Set<String> importedSimpleNames) {
            this.parsed = parsed;
            this.references = references;
            this.importedSimpleNames = Set.copyOf(importedSimpleNames);
        }

        @Override
        public Void visitCompilationUnit(CompilationUnitTree unit, Void unused) {
            scan(unit.getPackageAnnotations(), unused);
            scan(unit.getTypeDecls(), unused);
            return null;
        }

        @Override
        public Void visitMemberSelect(MemberSelectTree selection, Void unused) {
            QualifiedName qualified = qualifiedName(selection);
            if (qualified == null) {
                return super.visitMemberSelect(selection, unused);
            }
            String root = qualified.segments().getFirst();
            if (!resolvesInScope(root, qualified.root())) {
                references.add(new Reference(
                        String.join(".", qualified.segments()),
                        parsed.line(selection),
                        false));
            }
            return null;
        }

        private boolean resolvesInScope(String root, IdentifierTree identifier) {
            if (root.equals("this") || root.equals("super") || importedSimpleNames.contains(root)) {
                return true;
            }
            TreePath identifierPath = TreePath.getPath(getCurrentPath(), identifier);
            Element resolved = identifierPath == null ? null : parsed.trees().getElement(identifierPath);
            if (resolved != null) {
                return isValueOrTypeNamed(resolved, root);
            }
            for (Scope scope = parsed.trees().getScope(getCurrentPath());
                    scope != null;
                    scope = scope.getEnclosingScope()) {
                for (Element element : scope.getLocalElements()) {
                    if (isValueOrTypeNamed(element, root)) {
                        return true;
                    }
                }
            }
            return false;
        }

        private static boolean isValueOrTypeNamed(Element element, String name) {
            if (element == null || !element.getSimpleName().contentEquals(name)) {
                return false;
            }
            ElementKind kind = element.getKind();
            return kind.isVariable() || kind.isDeclaredType() || kind == ElementKind.TYPE_PARAMETER;
        }

        private static QualifiedName qualifiedName(MemberSelectTree selection) {
            ArrayDeque<String> segments = new ArrayDeque<>();
            Tree current = selection;
            while (current instanceof MemberSelectTree member) {
                segments.addFirst(member.getIdentifier().toString());
                current = member.getExpression();
            }
            if (!(current instanceof IdentifierTree identifier)) {
                return null;
            }
            segments.addFirst(identifier.getName().toString());
            return new QualifiedName(List.copyOf(segments), identifier);
        }

        private record QualifiedName(List<String> segments, IdentifierTree root) {
        }
    }

    private static final class StringSource extends SimpleJavaFileObject {
        private final String source;

        StringSource(String source) {
            super(URI.create("string:///GuardrailInput.java"), Kind.SOURCE);
            this.source = source;
        }

        @Override
        public CharSequence getCharContent(boolean ignoreEncodingErrors) {
            return source;
        }
    }
}
