import com.jinxin.practice.MyHttpsClient;
import org.junit.jupiter.api.Test;

/**
 * @author Jinxin
 * created at 2022/1/13 16:52
 **/
public class TestClient {

    @Test
    public void testMyClient() {
        MyHttpsClient client = new MyHttpsClient();
        int code = client.postCode(null, "https://my.mutual.auth.com", "");
        System.out.println("response code : " + code);
    }
}
