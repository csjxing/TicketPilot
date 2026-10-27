package cn.local.tickethelper.cloud;

import org.junit.Test;
import static org.junit.Assert.*;

public final class ApkIdentityTest {
    @Test public void exactSameSignedNewerBuildCanProceedToSystemInstaller() throws Exception {
        assertEquals(ApkIdentity.Reason.READY, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", CloudFixtures.SIGNER, 19, CloudFixtures.SIGNER));
    }
    @Test public void manifestVersionPackageAndCurrentVersionAreIndependentlyBound() throws Exception {
        assertEquals(ApkIdentity.Reason.PACKAGE_MISMATCH, check("another.package", 20, "0.2.4-preview", CloudFixtures.SIGNER, 19, CloudFixtures.SIGNER));
        assertEquals(ApkIdentity.Reason.VERSION_MISMATCH, check(CloudRelease.PACKAGE_NAME, 21, "0.2.4-preview", CloudFixtures.SIGNER, 19, CloudFixtures.SIGNER));
        assertEquals(ApkIdentity.Reason.VERSION_MISMATCH, check(CloudRelease.PACKAGE_NAME, 20, "other", CloudFixtures.SIGNER, 19, CloudFixtures.SIGNER));
        assertEquals(ApkIdentity.Reason.NOT_NEWER, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", CloudFixtures.SIGNER, 20, CloudFixtures.SIGNER));
        assertEquals(ApkIdentity.Reason.NOT_NEWER, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", CloudFixtures.SIGNER, 21, CloudFixtures.SIGNER));
    }
    @Test public void manifestCertificateCannotReplaceCurrentInstalledSigner() throws Exception {
        assertEquals(ApkIdentity.Reason.SIGNATURE_MISMATCH, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", CloudFixtures.SIGNER, 19, "b".repeat(64)));
        assertEquals(ApkIdentity.Reason.SIGNATURE_MISMATCH, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", "c".repeat(64), 19, "c".repeat(64)));
        assertEquals(ApkIdentity.Reason.UNREADABLE, check(CloudRelease.PACKAGE_NAME, 20, "0.2.4-preview", "", 19, CloudFixtures.SIGNER));
    }
    private ApkIdentity.Reason check(String name, long code, String version, String archiveSigner, long installed, String signer) throws Exception {
        return ApkIdentity.check(CloudFixtures.release(), name, code, version, archiveSigner, installed, signer);
    }
}
