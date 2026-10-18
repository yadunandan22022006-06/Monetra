package com.monetra;

import com.google.zxing.BarcodeFormat;
import com.google.zxing.BinaryBitmap;
import com.google.zxing.DecodeHintType;
import com.google.zxing.EncodeHintType;
import com.google.zxing.MultiFormatReader;
import com.google.zxing.NotFoundException;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.EnumMap;
import java.util.Map;

/**
 * "My QR": a QR code holding the user's Monetra payment link, and a decoder
 * so a photo or screenshot of a QR code can be read on any browser.
 */
@RestController
@RequestMapping("/api/qr")
public class QrController {

    private static final int MAX_IMAGE_BYTES = 4_000_000;

    private final UserAccountRepository users;

    public QrController(UserAccountRepository users) {
        this.users = users;
    }

    /** The logged-in user's own QR code as an SVG image. */
    @GetMapping("/me")
    public ResponseEntity<?> myQr(HttpSession session) {
        Long userId = SessionUser.id(session);

        UserAccount user = userId == null ? null : users.findById(userId).orElse(null);

        if (user == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
        }

        if (user.getPhoneKey() == null) {
            return ResponseEntity.badRequest().body(Map.of("error", "Add a mobile number to get a QR code."));
        }

        try {
            return ResponseEntity.ok()
                    .contentType(MediaType.valueOf("image/svg+xml"))
                    .header("Cache-Control", "no-store")
                    .body(svg(link(user)));

        } catch (Exception e) {
            return ResponseEntity.internalServerError().body(Map.of("error", "Could not draw the QR code."));
        }
    }

    /**
     * Reads a QR code from an uploaded image (raw bytes in the request body). The image is only read, never saved.
     * With live=1 (frames from the camera, sent again and again until a code shows) an image with no code in it is
     * an ordinary answer ({"found": false}), not an error.
     */
    @PostMapping(value = "/decode", consumes = MediaType.APPLICATION_OCTET_STREAM_VALUE)
    public ResponseEntity<?> decode(@RequestBody byte[] image, @RequestParam(value = "live", defaultValue = "") String live,
            HttpSession session) {
        if (SessionUser.id(session) == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(Map.of("error", "Please log in first."));
        }

        if (image == null || image.length == 0 || image.length > MAX_IMAGE_BYTES) {
            return ResponseEntity.badRequest().body(Map.of("error", "Choose an image smaller than 4 MB."));
        }

        try {
            BufferedImage picture = ImageIO.read(new ByteArrayInputStream(image));

            if (picture == null) {
                return ResponseEntity.badRequest().body(Map.of("error", "That file is not an image."));
            }

            Map<DecodeHintType, Object> hints = new EnumMap<>(DecodeHintType.class);
            hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);
            hints.put(DecodeHintType.POSSIBLE_FORMATS, java.util.List.of(BarcodeFormat.QR_CODE));

            BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(new BufferedImageLuminanceSource(picture)));

            String text = new MultiFormatReader().decode(bitmap, hints).getText();

            return ResponseEntity.ok(Map.of("text", text, "payee", parse(text)));

        } catch (NotFoundException e) {
            if ("1".equals(live)) {
                return ResponseEntity.ok(Map.of("found", false));
            }

            return ResponseEntity.badRequest().body(Map.of("error", "No QR code found in that image."));

        } catch (Exception e) {
            return ResponseEntity.badRequest().body(Map.of("error", "Could not read that image."));
        }
    }

    /** What a Monetra QR holds. Anyone's phone camera can read it. */
    static String link(UserAccount user) {
        return "monetra://pay?phone=" + user.getPhoneKey();
    }

    /**
     * Pulls a mobile number and a name out of scanned text.
     * Understands Monetra links, UPI links (upi://pay?pa=9876543210@bank&amp;pn=Name) and plain numbers.
     */
    static Map<String, String> parse(String text) {
        String phone = null;
        String name = null;

        if (text != null) {
            String t = text.trim();

            String lower = t.toLowerCase();

            if (lower.startsWith("monetra://") || lower.startsWith("upi://")) {
                String query = t.contains("?") ? t.substring(t.indexOf('?') + 1) : "";

                for (String pair : query.split("&")) {
                    int eq = pair.indexOf('=');

                    if (eq < 0) {
                        continue;
                    }

                    String k = pair.substring(0, eq).toLowerCase();
                    String v = java.net.URLDecoder.decode(pair.substring(eq + 1), StandardCharsets.UTF_8);

                    if (k.equals("phone")) {
                        phone = Phones.key(v);
                    } else if (k.equals("pa") && phone == null) {
                        phone = Phones.key(v.contains("@") ? v.substring(0, v.indexOf('@')) : v);
                    } else if (k.equals("pn")) {
                        name = v.length() > 60 ? v.substring(0, 60) : v;
                    }
                }

            } else {
                phone = Phones.key(t);
            }
        }

        Map<String, String> out = new java.util.LinkedHashMap<>();
        out.put("phone", phone == null ? "" : phone);
        out.put("name", name == null ? "" : name);
        return out;
    }

    /** Draws a QR code as a small SVG, using a plain path per dark module. */
    static String svg(String content) throws Exception {
        Map<EncodeHintType, Object> hints = new EnumMap<>(EncodeHintType.class);
        hints.put(EncodeHintType.MARGIN, 1);
        hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");

        BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 0, 0, hints);

        int w = matrix.getWidth();
        int h = matrix.getHeight();

        StringBuilder path = new StringBuilder();

        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if (matrix.get(x, y)) {
                    path.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
                }
            }
        }

        return "<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 " + w + " " + h
                + "\" shape-rendering=\"crispEdges\"><rect width=\"100%\" height=\"100%\" fill=\"#ffffff\"/>"
                + "<path d=\"" + path + "\" fill=\"#0a1030\"/></svg>";
    }
}
