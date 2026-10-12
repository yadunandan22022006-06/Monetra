package com.monetra;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * Optional demo accounts so judges and testers can try Monetra without email OTP.
 * Created only when monetra.seed-demo-users=true. Never enable this on a real deployment.
 *
 * Both accounts have a mobile number, so they can find and pay each other:
 *   demo1@monetra.test  (Asha Demo, 9000000001) has sample limits, an EMI, a goal and expenses.
 *   demo2@monetra.test  (Ravi Demo, 9000000002) is empty, a second person to pay and be paid by.
 */
@Component
@ConditionalOnProperty(name = "monetra.seed-demo-users", havingValue = "true")
public class DemoData implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoData.class);

    private final UserAccountRepository users;
    private final UserDataRepository store;
    private final String password;

    public DemoData(
            UserAccountRepository users,
            UserDataRepository store,
            @Value("${monetra.demo-password}") String password) {

        this.users = users;
        this.store = store;
        this.password = password;
    }

    @Override
    public void run(ApplicationArguments args) {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder();

        UserAccount asha = ensure(encoder, "demo1@monetra.test", "9000000001", "Asha Demo");

        ensure(encoder, "demo2@monetra.test", "9000000002", "Ravi Demo");

        if (store.findById(asha.getId()).isEmpty()) {
            store.save(new UserData(asha.getId(), sampleRecords()));
        }

        log.info("Demo accounts are ready: demo1@monetra.test and demo2@monetra.test");
    }

    private UserAccount ensure(BCryptPasswordEncoder encoder, String email, String phone, String name) {
        return users.findByEmail(email)
                .orElseGet(() -> users.save(new UserAccount(email, encoder.encode(password), phone, name)));
    }

    private static String sampleRecords() {
        LocalDate today = LocalDate.now();

        return "{"
                + "\"limits\":{\"daily\":500,\"weekly\":3000,\"monthly\":12000,\"yearly\":0},"
                + "\"dailyLimit\":500,"
                + "\"income\":[],\"payments\":[],\"savings\":[{\"id\":1,\"name\":\"Pocket money saved\",\"amount\":1500,\"period\":\"Monthly\",\"date\":\"" + today + "\"}],"
                + "\"expenses\":["
                + expense(11, "Lunch", 180, today)
                + "," + expense(12, "Books", 650, today.minusDays(1))
                + "," + expense(13, "Bus pass", 300, today.minusDays(2))
                + "," + expense(14, "Snacks", 90, today.minusDays(3))
                + "," + expense(15, "Groceries", 720, today.minusDays(5))
                + "],"
                + "\"loans\":[{\"id\":21,\"name\":\"Laptop EMI\",\"amount\":2000,\"due\":\"" + today.plusDays(3) + "\",\"notes\":\"4 payments left\"}],"
                + "\"goals\":[{\"id\":31,\"name\":\"New phone\",\"target\":20000,\"saved\":8000}]"
                + "}";
    }

    private static String expense(int id, String name, int amount, LocalDate date) {
        return "{\"id\":" + id + ",\"name\":\"" + name + "\",\"amount\":" + amount
                + ",\"date\":\"" + date + "\",\"source\":\"manual\"}";
    }
}
