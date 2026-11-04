package mc2pc.app;

import java.util.Arrays;

/** Ponto de entrada: setup | relayer | client ... */
public final class Main {
    private Main() {
    }

    public static void main(String[] args) throws Exception {
        String cmd = args.length > 0 ? args[0] : "";
        String[] rest = args.length > 0 ? Arrays.copyOfRange(args, 1, args.length) : args;
        try {
            switch (cmd) {
                case "setup" -> Setup.run();
                case "relayer" -> Relayer.run();
                case "client" -> Client.run(rest);
                case "explorer" -> Explorer.run(rest);
                default -> {
                    System.err.println("uso: mc2pc (setup | relayer | client <comando> | explorer [arquivo.html])");
                    System.exit(1);
                }
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            System.err.println("ERRO: " + e.getMessage());
            System.exit(1);
        }
    }
}
