import ai.opencode.p0probe.TarGz;

import java.io.File;
import java.io.FileInputStream;

/** Host-side test: extract the REAL opencode tarball with TarGz, verify sha256. */
public class TestTarGz {
    public static void main(String[] args) throws Exception {
        File tarball = new File(args[0]);
        File dest = new File(args[1]);
        long t0 = System.currentTimeMillis();
        String[] r = TarGz.extractBinary(new FileInputStream(tarball), dest);
        long ms = System.currentTimeMillis() - t0;
        System.out.println("entry : " + r[0]);
        System.out.println("size  : " + r[1] + " (" + (Long.parseLong(r[1]) / (1024 * 1024)) + " MB)");
        System.out.println("time  : " + ms + " ms");
        System.out.println("dest  : " + dest.getAbsolutePath() + " exists=" + dest.exists()
                + " actualSize=" + dest.length());
        // sha256
        java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
        FileInputStream in = new FileInputStream(dest);
        byte[] b = new byte[1 << 16];
        int n;
        while ((n = in.read(b)) != -1) md.update(b, 0, n);
        in.close();
        StringBuilder sb = new StringBuilder();
        for (byte d : md.digest()) sb.append(String.format("%02x", d));
        System.out.println("sha256: " + sb);
        if (dest.length() != Long.parseLong(r[1])) {
            System.out.println("RESULT: FAIL (size mismatch)");
            System.exit(1);
        }
        System.out.println("RESULT: PASS");
    }
}
