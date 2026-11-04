package mc2pc.common.cc;

import mc2pc.common.model.Trust;
import org.hyperledger.fabric.shim.ChaincodeException;

import javax.naming.InvalidNameException;
import javax.naming.ldap.LdapName;
import javax.naming.ldap.Rdn;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Date;
import java.util.List;

/** Verificação de certificados e assinaturas ECDSA (mesmo esquema do Fabric: ECDSA sobre SHA-256). */
public final class Crypto {
    private Crypto() {
    }

    public static X509Certificate parseCert(String pem) {
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(new ByteArrayInputStream(pem.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new ChaincodeException("certificado PEM inválido");
        }
    }

    /**
     * Confere se o certificado foi emitido diretamente pela CA raiz de alguma das orgs e está válido em {@code now}.
     * (CAs intermediárias não são suportadas — o cryptogen emite direto da raiz.)
     */
    public static X509Certificate verifyCert(String certPem, List<Trust.OrgTrust> orgs, Instant now) {
        X509Certificate cert = parseCert(certPem);
        Date at = Date.from(now);
        for (Trust.OrgTrust org : orgs) {
            for (String rootPem : org.rootCerts) {
                X509Certificate root = parseCert(rootPem);
                if (!root.getSubjectX500Principal().equals(cert.getIssuerX500Principal())) {
                    continue;
                }
                try {
                    cert.verify(root.getPublicKey());
                    cert.checkValidity(at);
                    root.checkValidity(at);
                    return cert;
                } catch (GeneralSecurityException e) {
                    // tenta a próxima raiz
                }
            }
        }
        throw new ChaincodeException("certificado '" + cert.getSubjectX500Principal().getName()
                + "' não emitido pelas orgs confiáveis (ou fora da validade)");
    }

    public static void verifySignature(X509Certificate cert, byte[] msg, byte[] derSig) {
        try {
            Signature s = Signature.getInstance("SHA256withECDSA");
            s.initVerify(cert.getPublicKey());
            s.update(msg);
            if (s.verify(derSig)) {
                return;
            }
        } catch (GeneralSecurityException e) {
            // assinatura malformada
        }
        throw new ChaincodeException("assinatura inválida");
    }

    /** Valores de OU do subject (NodeOUs do MSP: peer, admin, client, orderer). */
    public static List<String> organizationalUnits(X509Certificate cert) {
        try {
            return new LdapName(cert.getSubjectX500Principal().getName()).getRdns().stream()
                    .filter(r -> r.getType().equalsIgnoreCase("OU"))
                    .map(Rdn::getValue).map(Object::toString).toList();
        } catch (InvalidNameException e) {
            return List.of();
        }
    }
}
