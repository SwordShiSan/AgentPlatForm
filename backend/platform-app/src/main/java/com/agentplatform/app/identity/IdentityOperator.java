package com.agentplatform.app.identity;

import com.agentplatform.domain.identity.IdentityFailure;
import com.agentplatform.domain.identity.IdentityRules;
import com.agentplatform.domain.identity.IdentitySecrets;
import com.agentplatform.domain.identity.IdentityStore;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.time.Instant;
import java.util.Set;
import java.util.UUID;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** Offline trusted operator only; never a public bootstrap endpoint. */
@Component
@Profile("identity-ops")
public final class IdentityOperator implements ApplicationRunner {
    private final IdentityStore store;
    private final IdentitySecrets secrets;
    public IdentityOperator(IdentityStore store, IdentitySecrets secrets) { this.store = store; this.secrets = secrets; }
    private String option(ApplicationArguments args, String key) {
        var values = args.getOptionValues(key);
        if (values == null || values.size() != 1) throw new IdentityFailure(400, "MISSING_OPERATOR_OPTION");
        return values.getFirst();
    }
    @Override public void run(ApplicationArguments args) throws Exception {
        String action = option(args, "identity-action");
        if (!Set.of("bootstrap", "create", "disable", "reset-password").contains(action)) throw new IdentityFailure(400, "INVALID_OPERATOR_ACTION");
        String name = IdentityRules.username(option(args, "username"));
        if (args.containsOption("password")) throw new IdentityFailure(400, "PASSWORD_ARGUMENT_FORBIDDEN");
        if (action.equals("disable")) { store.changeUser(name, null, true, Instant.now()); System.out.println("Identity disabled; sessions revoked."); return; }
        String password;
        if (args.containsOption("password-file")) {
            Path file = Path.of(option(args, "password-file"));
            if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file)>1024) throw new IdentityFailure(400, "INVALID_PASSWORD_FILE");
            // Use this path inside a Linux operator container. Windows operators use the console.
            var permissions = Files.getPosixFilePermissions(file);
            if (permissions.stream().anyMatch(p -> Set.of(PosixFilePermission.GROUP_READ, PosixFilePermission.GROUP_WRITE, PosixFilePermission.GROUP_EXECUTE, PosixFilePermission.OTHERS_READ, PosixFilePermission.OTHERS_WRITE, PosixFilePermission.OTHERS_EXECUTE).contains(p))) throw new IdentityFailure(400, "PASSWORD_FILE_NOT_PRIVATE");
            password = Files.readString(file);
            if (password.endsWith("\r\n")) password = password.substring(0, password.length()-2);
            else if (password.endsWith("\n")) password = password.substring(0, password.length()-1);
        } else {
            if (System.console() == null) throw new IdentityFailure(400, "PRIVATE_PASSWORD_INPUT_REQUIRED");
            char[] input = System.console().readPassword("Password: ");
            if (input == null) throw new IdentityFailure(400, "PRIVATE_PASSWORD_INPUT_REQUIRED");
            password = new String(input); java.util.Arrays.fill(input, '\0');
        }
        IdentityRules.password(password);
        if (action.equals("reset-password")) { store.changeUser(name, secrets.passwordHash(password), false, Instant.now()); System.out.println("Password reset; sessions revoked."); return; }
        UUID request = UUID.fromString(option(args, "request-id"));
        UUID id = store.provision(request, secrets.hash(name + ":" + action), name, secrets.passwordHash(password), action.equals("bootstrap"));
        var user = store.user(id);
        if (user == null || !secrets.matches(password, user.passwordHash())) throw new IdentityFailure(409, "IDEMPOTENCY_CONFLICT");
        System.out.println("Identity provisioned: " + id);
    }
}
