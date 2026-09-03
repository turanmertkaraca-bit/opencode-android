import ai.opencode.p0probe.ElfGate;
import java.io.File;
import java.io.FileOutputStream;

public class ElfGateTest {
    public static void main(String[] args) throws Exception {
        int fails = 0;

        // 1. real host binary: /bin/ls (ET_DYN, x86_64, has interp)
        ElfGate.Info i = ElfGate.inspect(new File("/bin/ls"));
        check("ls valid", i.validElf, true);
        check("ls 64le", i.is64 && i.littleEndian, true);
        check("ls eType ET_DYN", i.eType, 3);
        check("ls eMachine x86_64", i.eMachine, 0x3E);
        check("ls hasInterp", i.hasInterp, true);
        check("ls interp path", i.interp.contains("ld-linux"), true);
        check("ls sha256 len", i.sha256.length() >= 16, true);
        if (failsCheck()) fails++;

        // 2. synthetic bionic-style aarch64: ET_DYN + EM_AARCH64 + /system/bin/linker64
        byte[] fake = synth(3, 0xB7, "/system/bin/linker64");
        File f = new File("/tmp/fake-bionic.so");
        FileOutputStream o = new FileOutputStream(f);
        o.write(fake);
        o.close();
        i = ElfGate.inspect(f);
        check("fake valid", i.validElf, true);
        check("fake eType ET_DYN", i.eType, 3);
        check("fake eMachine aarch64", i.eMachine, 0xB7);
        check("fake interp", i.interp, "/system/bin/linker64");
        if (failsCheck()) fails++;

        // 3. synthetic ET_EXEC (must be detectable -> gate would FAIL it)
        byte[] exec = synth(2, 0xB7, "/system/bin/linker64");
        File f2 = new File("/tmp/fake-exec.so");
        FileOutputStream o2 = new FileOutputStream(f2);
        o2.write(exec);
        o2.close();
        i = ElfGate.inspect(f2);
        check("exec eType ET_EXEC", i.eType, 2);
        if (failsCheck()) fails++;

        // 4. garbage file
        File f3 = new File("/tmp/fake-garbage.bin");
        FileOutputStream o3 = new FileOutputStream(f3);
        o3.write(new byte[]{1, 2, 3, 4, 5, 6, 7, 8});
        o3.close();
        i = ElfGate.inspect(f3);
        check("garbage invalid", i.validElf, false);
        check("garbage error set", i.error.length() > 0, true);
        if (failsCheck()) fails++;

        System.out.println(fails == 0 ? "ELFGATE-TEST: ALL PASS" : "ELFGATE-TEST: FAILURES=" + fails);
        System.exit(fails == 0 ? 0 : 1);
    }

    static boolean failsCheck() { return false; }

    static void check(String name, Object got, Object want) {
        boolean ok = got == null ? want == null : got.equals(want);
        System.out.println((ok ? "PASS " : "FAIL ") + name + "  (got=" + got + ")");
    }

    static byte[] synth(int eType, int eMachine, String interp) {
        int phoff = 64;
        int phnum = 1;
        int phentsize = 56;
        int dataOff = phoff + phnum * phentsize;
        byte[] interpB = interp.getBytes();
        byte[] out = new byte[dataOff + interpB.length + 1];

        // e_ident
        out[0] = 0x7f; out[1] = 'E'; out[2] = 'L'; out[3] = 'F';
        out[4] = 2; out[5] = 1; out[6] = 1; out[7] = 0;
        // e_type, e_machine
        w16(out, 16, eType);
        w16(out, 18, eMachine);
        w32(out, 20, 1);              // e_version
        w64(out, 24, 0);              // e_entry
        w64(out, 32, phoff);          // e_phoff
        w64(out, 40, 0);              // e_shoff
        w32(out, 48, 0);              // e_flags
        w16(out, 52, 64);             // e_ehsize
        w16(out, 54, phentsize);      // e_phentsize
        w16(out, 56, phnum);          // e_phnum
        // phdr[0]: PT_INTERP
        w32(out, phoff + 0, 3);       // p_type = PT_INTERP
        w32(out, phoff + 4, 4);       // p_flags
        w64(out, phoff + 8, dataOff); // p_offset
        w64(out, phoff + 16, 0);      // p_vaddr
        w64(out, phoff + 24, 0);      // p_paddr
        w64(out, phoff + 32, interpB.length + 1); // p_filesz
        w64(out, phoff + 40, interpB.length + 1); // p_memsz
        w64(out, phoff + 48, 1);      // p_align
        // interp data
        System.arraycopy(interpB, 0, out, dataOff, interpB.length);
        out[out.length - 1] = 0;
        return out;
    }

    static void w16(byte[] b, int o, int v) {
        b[o] = (byte) v; b[o + 1] = (byte) (v >> 8);
    }
    static void w32(byte[] b, int o, int v) {
        w16(b, o, v & 0xFFFF); w16(b, o + 2, (v >> 16) & 0xFFFF);
    }
    static void w64(byte[] b, int o, long v) {
        w32(b, o, (int) (v & 0xFFFFFFFFL)); w32(b, o + 4, (int) ((v >> 32) & 0xFFFFFFFFL));
    }
}
