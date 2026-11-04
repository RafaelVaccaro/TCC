package mc2pc.common.model;

import java.util.List;

/**
 * O que uma cadeia precisa saber sobre outra para aceitar provas dela:
 * canal, chaincode, certificados raiz (CA de identidade) das orgs e a política de endosso.
 */
public class Trust {
    public String channel;
    public String chaincode;
    public List<OrgTrust> orgs;
    public String policy; // MAJORITY | ALL | ANY | <n>

    public static class OrgTrust {
        public String mspId;
        public List<String> rootCerts; // PEM

        public OrgTrust() {
        }

        public OrgTrust(String mspId, List<String> rootCerts) {
            this.mspId = mspId;
            this.rootCerts = rootCerts;
        }
    }

    /** Número de orgs distintas que precisam endossar. */
    public int required() {
        int n = orgs == null ? 0 : orgs.size();
        String p = policy == null ? "MAJORITY" : policy.toUpperCase();
        switch (p) {
            case "", "MAJORITY":
                return n / 2 + 1;
            case "ALL":
                return n;
            case "ANY":
                return 1;
            default:
                try {
                    int k = Integer.parseInt(p);
                    if (k >= 1 && k <= n) {
                        return k;
                    }
                } catch (NumberFormatException ignored) {
                    // cai no erro abaixo
                }
                throw new IllegalArgumentException("política inválida '" + policy + "' para " + n + " orgs");
        }
    }
}
