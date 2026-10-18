package com.monetra;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.http.HttpResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The server reads a QR code from a picture: an uploaded photo, or a frame of the live camera (sent by browsers that
 * have no QR reader of their own, such as most desktop browsers).
 */
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "spring.datasource.url=jdbc:h2:mem:monetra-qr-decode",
                "spring.mail.host=localhost",
                "paypal.client-id=",
                "paypal.client-secret=",
                "anthropic.api-key=",
                "ai.base-url=",
                "monetra.score.daily-job=false"
        })
class QrDecodeTest {

    @LocalServerPort
    int port;

    @Autowired
    UserAccountRepository users;

    private Http signedIn() throws Exception {
        if (users.findByEmail("qr@test.dev").isEmpty()) {
            users.save(new UserAccount("qr@test.dev", new BCryptPasswordEncoder().encode("password-1"), "9000000201", "Qr Tester"));
        }

        return Http.login(port, "qr@test.dev", "password-1");
    }

    private static byte[] qrPicture(String text) throws Exception {
        BitMatrix matrix = new QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 300, 300);

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        MatrixToImageWriter.writeToStream(matrix, "PNG", out);

        return out.toByteArray();
    }

    private static byte[] blankPicture() throws Exception {
        BufferedImage image = new BufferedImage(200, 200, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = image.createGraphics();

        g.setColor(Color.WHITE);
        g.fillRect(0, 0, 200, 200);
        g.dispose();

        ByteArrayOutputStream out = new ByteArrayOutputStream();

        ImageIO.write(image, "PNG", out);

        return out.toByteArray();
    }

    @Test
    void aQrPictureIsReadAndTheMobileNumberIsFound() throws Exception {
        Http me = signedIn();

        byte[] picture = qrPicture("monetra://pay?phone=9000000202");

        /* The same picture whether it was uploaded or is a frame of the live camera. */
        for (String path : new String[] { "/api/qr/decode", "/api/qr/decode?live=1" }) {
            HttpResponse<String> res = me.postBytes(path, picture);

            assertEquals(200, res.statusCode(), res.body());
            assertTrue(res.body().contains("monetra://pay?phone=9000000202"), res.body());
            assertTrue(res.body().contains("9000000202"), res.body());
        }
    }

    @Test
    void aPictureWithNoCodeIsAnErrorForAnUploadButAnOrdinaryAnswerForTheLiveCamera() throws Exception {
        Http me = signedIn();

        HttpResponse<String> upload = me.postBytes("/api/qr/decode", blankPicture());

        assertEquals(400, upload.statusCode(), upload.body());
        assertTrue(upload.body().contains("No QR code found"), upload.body());

        HttpResponse<String> live = me.postBytes("/api/qr/decode?live=1", blankPicture());

        assertEquals(200, live.statusCode(), live.body());
        assertTrue(live.body().contains("\"found\":false"), live.body());
    }

    @Test
    void readingAPictureNeedsASignedInUser() throws Exception {
        assertEquals(401, new Http(port).postBytes("/api/qr/decode?live=1", blankPicture()).statusCode());
    }
}
