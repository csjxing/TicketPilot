package cn.local.tickethelper.relay;

import org.junit.Test;
import java.io.IOException;
import java.util.List;
import static org.junit.Assert.*;

public final class RelayFailureMessageTest {
    @Test public void exactLocalFailuresRetainActionableInstructions() {
        for (String message : List.of(
                "此 Key 没有文件中转站的管理权限，请在远见启用中转站并选择管理权限。",
                "文件中转站 Key 已失效或被撤销，请重新连接。",
                "请填写文件中转站的 HTTPS 网站地址，不含账号、参数或其他路径。",
                "待发送诊断已变化，请重新预览后再发送；没有上传新的内容。",
                "上次诊断属于旧连接，请先在中转站核查，再明确清除本机待发送副本。",
                "本机诊断身份未能保存或读取，请重启应用后再试；购票资料未改变。",
                "诊断序号未保存。")) assertEquals(message, RelayFailureMessage.display(new IOException(message), false));
    }
    @Test public void unknownBodiesNamesCredentialsPathsAndUrlsNeverEcho() {
        for (String message : List.of("synthetic viewer name", "fst_" + "A".repeat(43), "Authorization: Bearer synthetic-secret",
                "https://bucket.example.com/a?signature=private", "/data/user/0/synthetic/private.json",
                "{\"error\":\"private order details\"}", "<html>private page</html>", "", "x".repeat(40000)))
            assertEquals(RelayFailureMessage.UNKNOWN, RelayFailureMessage.display(new IOException(message), false));
    }
    @Test public void knownPrefixesCannotCarryAppendedOrPrecedingPrivateText() {
        String known = "请先连接文件中转站。";
        for (String changed : List.of(known + "secret", "secret" + known, known + "\nprivate", " " + known, known + " "))
            assertEquals(RelayFailureMessage.UNKNOWN, RelayFailureMessage.display(new IOException(changed), false));
    }
    @Test public void causesAreNotTraversedAndNullIsSafe() {
        IOException error = new IOException("unknown wrapper", new IOException("文件中转站 Key 已失效或被撤销，请重新连接。"));
        assertEquals(RelayFailureMessage.UNKNOWN, RelayFailureMessage.display(error, false));
        assertEquals(RelayFailureMessage.UNKNOWN, RelayFailureMessage.display(null, false));
        assertEquals(RelayFailureMessage.UNKNOWN, RelayFailureMessage.display(new IOException((String) null), false));
    }
    @Test public void cancellationAlwaysUsesAFixedMessage() {
        assertEquals(RelayFailureMessage.CANCELLED, RelayFailureMessage.display(new IOException("synthetic private body"), true));
        assertEquals(RelayFailureMessage.CANCELLED, RelayFailureMessage.display(null, true));
    }
}
