package llm_context;

import java.util.Arrays;

/** Minimal JVM entry point that keeps Clojure namespaces loadable from source. */
public final class Launcher {
    private Launcher() {}

    public static void main(String[] args) {
        String[] clojureArgs = new String[args.length + 2];
        clojureArgs[0] = "-m";
        clojureArgs[1] = "llm-context.main";
        System.arraycopy(args, 0, clojureArgs, 2, args.length);
        clojure.main.main(clojureArgs);
    }
}
