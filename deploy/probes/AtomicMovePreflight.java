import java.nio.file.*;

/** Launcher preflight only: check the same atomic replacement used by SDK fixture state. */
public final class AtomicMovePreflight {
    public static void main(String[] args)throws Exception {
        Path root=Path.of(args[0]).toAbsolutePath().normalize();
        Path directory=Files.createTempDirectory(root,"atomic-move-");
        Path target=directory.resolve("state.json"),pending=directory.resolve("state.json.pending");
        Files.writeString(target,"old");Files.writeString(pending,"new");
        Files.move(pending,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);
        if(!Files.readString(target).equals("new"))throw new IllegalStateException("Atomic move mismatch");
        Files.delete(target);Files.delete(directory);
        System.out.println("ATOMIC_MOVE_PREFLIGHT_PASS");
    }
}
