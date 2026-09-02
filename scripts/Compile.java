import java.io.File;
import java.nio.file.*;
import java.util.*;
import javax.tools.*;

/** Tiny in-process javac driver (the runtime has jdk.compiler but no javac binary). */
public class Compile {
    public static void main(String[] a) throws Exception {
        JavaCompiler jc = ToolProvider.getSystemJavaCompiler();
        if (jc == null) { System.out.println("NO SYSTEM COMPILER"); System.exit(2); }
        List<String> args = new ArrayList<>();
        args.add("-d"); args.add(a[0]);
        for (int i = 1; i < a.length; i++) args.add(a[i]);
        int rc = jc.run(null, null, null, args.toArray(new String[0]));
        System.out.println("javac rc=" + rc);
        System.exit(rc);
    }
}
