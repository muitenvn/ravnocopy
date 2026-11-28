# Điều Khoản Người Dùng & Chính Sách Quyền Riêng Tư / Terms of Use & Privacy Policy

**Phiên bản / Version:** 2.2  
**Ngày có hiệu lực / Effective Date:** 27/11/2026 *(November 27, 2026)*  
**Phạm vi áp dụng / Scope:** Ứng dụng **Ravno** (`com.aistudio.cloudplay.kbtolt` — Điện thoại, Máy tính bảng, Android TV), ứng dụng tay cầm **Ravno Pad** (`com.muitenvn.cloudplay.pad`), và trang web dự án (`https://muitenvn.github.io/Ravno-App/`).  
**Bản web đầy đủ / Full Interactive Web Version:** [https://muitenvn.github.io/Ravno-App/terms.html](https://muitenvn.github.io/Ravno-App/terms.html)

> Khi tải xuống, cài đặt, bấm đồng ý trong hộp thoại điều khoản hoặc tiếp tục sử dụng **Ravno** / **Ravno Pad**, bạn xác nhận đã đọc, hiểu và đồng ý với toàn bộ các điều khoản dưới đây.  
> *By downloading, installing, accepting the in-app terms dialog, or continuing to use **Ravno** / **Ravno Pad**, you confirm that you have read, understood, and agreed to the terms below.*

---

## 1. Bản chất ứng dụng & Tuyên bố không liên kết / Nature of the App & Non-Affiliation

- **Ravno** là trình duyệt chuyên biệt mã nguồn mở trên Android (`WebView` + lớp phủ Jetpack Compose) giúp truy cập trang web chính thức của **Xbox Cloud Gaming** (`xbox.com/play`, `play.xbox.com`) kèm tay cầm cảm ứng, giao diện Android TV, thông số WebRTC thực tế và công cụ mạng tùy chọn. **Ravno** không lưu trữ hay tự phát trò chơi từ máy chủ riêng.
- **Ravno Pad** là ứng dụng tay cầm không dây đi kèm, giúp dùng điện thoại điều khiển Ravno trên Android TV.
- Dự án được phát triển độc lập bởi cá nhân (**Nguyễn Thế Ngọc — `muitenvn`**), **KHÔNG** liên kết, không thuộc sở hữu, không được bảo trợ hay ủy quyền bởi **Microsoft Corporation**, **Xbox**, **Epic Games, Inc.** hay bất kỳ nhà phát hành trò chơi nào.
- *Ravno and Ravno Pad are independent open-source projects not affiliated with, owned by, authorized, or endorsed by Microsoft Corporation, Xbox, or Epic Games, Inc.*

---

## 2. Tài khoản Microsoft & Dịch vụ bên thứ ba / Microsoft Accounts & Third-Party Services

- Bạn đăng nhập trực tiếp bằng tài khoản Microsoft của mình trên trang chính thức (`login.live.com`, `login.microsoftonline.com`, `xbox.com`) qua HTTPS. Ứng dụng không đọc, không ghi log và không lưu mật khẩu, mã OTP/2FA hay thông tin thanh toán.
- Bạn tự chịu trách nhiệm tuân thủ *Thỏa thuận Dịch vụ Microsoft*, *Tiêu chuẩn Cộng đồng Xbox* và điều khoản của nhà phát hành trò chơi. Việc dùng trình duyệt tùy biến hoặc máy chủ Proxy/VPN công cộng để truy cập từ khu vực chưa hỗ trợ có thể tiềm ẩn rủi ro đối với tài khoản theo chính sách của bên thứ ba; người dùng tự cân nhắc và tự chịu trách nhiệm.

---

## 3. Kết nối Ravno Pad (LAN mặc định & Khác Wi-Fi tùy chọn) / Ravno Pad Connectivity

- **Mặc định (Cùng Wi-Fi / LAN):** Hai thiết bị tự tìm nhau nội bộ qua NSD (`_cloudplaypad._tcp`), xác thực bằng mã PIN 4 số + HMAC-SHA256, khóa 3 phút nếu sai 3 lần, truyền tín hiệu qua TCP/UDP cục bộ và tự nhả nút sau ~450 ms nếu mất kết nối.
- **Tùy chọn nâng cao — Khác Wi-Fi / 4G/5G (Mặc định TẮT):**
  - Chỉ hoạt động khi bạn chủ động bật và xác nhận trên TV để sinh mã 10 chữ số (`XXXXX-XXXXX`), sau đó nhập số ở mục *Nhập IP → Nâng cao (Mã)* trên Ravno Pad.
  - Dữ liệu tay cầm được mã hóa đầu-cuối (TLS cổng 8883 + PBKDF2-HMAC-SHA256 + ECDH P-256 + HKDF-SHA256 + AES-256-GCM) khi đi qua máy chủ trung gian MQTT công cộng (`broker.emqx.io` / `broker.hivemq.com`). Không sử dụng Firebase và không yêu cầu đăng nhập tài khoản Google.
  - Tự ưu tiên nâng cấp về đường LAN trực tiếp nếu hai máy liên lạc nội bộ được; mã tự hết hạn sau 15 phút rảnh; tự khóa 3 phút sau 3 lần sai; tự nhả toàn bộ nút bấm sau ~800 ms mất tín hiệu hoặc khi vào nền.
  - Nhà phát triển không vận hành các broker công cộng bên thứ ba nói trên nên không đảm bảo độ trễ hay thời gian hoạt động tuyệt đối của kênh khác Wi-Fi.

---

## 4. Máy chủ Proxy/VPN công cộng tùy chọn / Optional Public Proxy/VPN Routing

- Khi bật tính năng máy chủ trong tab *Máy chủ* của Ravno, kết nối chỉ áp dụng cô lập cho riêng ứng dụng Ravno (`VpnService.addAllowedApplication`).
- Danh sách máy chủ miễn phí lấy từ nguồn công cộng bên thứ ba (`vpngate.net`, `api.proxyscrape.com`) hoặc do bạn tự nhập. Nhà phát triển không sở hữu, không kiểm duyệt và không đảm bảo an toàn hay tốc độ của các máy chủ bên thứ ba này.
- Ứng dụng tự động ngắt proxy/VPN hoặc chuyển sang trình duyệt hệ thống khi bạn truy cập trang đăng nhập, thanh toán, mua hàng hoặc nạp mã của Microsoft.

---

## 5. Quyền riêng tư & Dữ liệu lưu cục bộ / Privacy & Local-Only Data

- **Không thu thập dữ liệu tập trung:** Không máy chủ theo dõi, không quảng cáo, không bán hay chia sẻ dữ liệu cá nhân.
- **Lưu trữ hoàn toàn trên thiết bị của bạn:** Cài đặt, bố cục tay cầm, danh sách game yêu thích, lịch sử phiên chơi, thiết bị LAN đã nhớ, nhật ký kỹ thuật cục bộ (không chứa mật khẩu/PIN/token/mã 10 số) và bộ nhớ đệm cập nhật chỉ lưu trong vùng nhớ riêng của ứng dụng.
- **Cảm biến Gyroscope:** Mặc định tắt; khi bật chỉ xử lý trực tiếp trên RAM của máy và tự dừng khi vào nền.
- **Xóa dữ liệu bất cứ lúc nào:** Bạn có thể xóa phiên đăng nhập, xóa nhật ký, quên thiết bị Ravno Pad trong app, hoặc xóa toàn bộ dữ liệu / gỡ cài đặt trong Cài đặt Android.

---

## 6. Kiểm tra cập nhật, Quyền hệ thống & Xác thực SHA-256 / Updates, Permissions & SHA-256

- **Kiểm tra cập nhật mỗi lần mở app:** Cả **Ravno** và **Ravno Pad** tự động kiểm tra bản mới từ kho chính thức `https://github.com/muitenvn/Ravno-App/releases` mỗi lần mở ứng dụng (không chờ 24 giờ, có ETag giảm băng thông). **Ravno Pad** hỗ trợ tự động tải tệp APK mới ngay trong app.
- **Xác thực toàn vẹn SHA-256:** Dù tải trực tiếp từ GitHub hay qua máy chủ phụ (mirror CDN), ứng dụng luôn kiểm tra tên miền HTTPS hợp lệ, cấu trúc gói APK, đúng mã gói và đối chiếu mã băm **SHA-256** chính thức trước khi gọi trình cài đặt Android.
- **Quyền hệ thống tối thiểu:**
  - **Ravno:** `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `VIBRATE`, `WAKE_LOCK`, `FOREGROUND_SERVICE`, `FOREGROUND_SERVICE_SPECIAL_USE`, `MODIFY_AUDIO_SETTINGS`, `RECORD_AUDIO` (tùy chọn khi bật voice chat), `POST_NOTIFICATIONS`, `REQUEST_INSTALL_PACKAGES`.
  - **Ravno Pad:** Chỉ xin đúng 7 quyền: `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE`, `CHANGE_WIFI_MULTICAST_STATE`, `VIBRATE`, `WAKE_LOCK`, `REQUEST_INSTALL_PACKAGES` (chỉ dùng khi bạn cài bản cập nhật đã xác thực SHA-256). Không xin quyền micro, camera hay vị trí.

---

## 7. Chơi công bằng & Hành vi bị cấm / Fair Play & Prohibited Conduct

- Ravno và Ravno Pad chỉ cung cấp tín hiệu tay cầm Xbox tiêu chuẩn 1:1. Nghiêm cấm sử dụng ứng dụng cho hành vi gian lận, phá hoại mạng, gian lận thanh toán hoặc vi phạm pháp luật.

---

## 8. Miễn trừ bảo đảm, Giới hạn trách nhiệm & Luật áp dụng / Disclaimers & Governing Law

- Ứng dụng được cung cấp theo nguyên trạng (**"AS IS"**) hoàn toàn miễn phí cho mục đích cá nhân; nhà phát triển không bảo đảm dịch vụ sẽ không bao giờ gián đoạn do thay đổi từ Microsoft/Xbox, nhà mạng hay máy chủ bên thứ ba.
- Điều khoản này được điều chỉnh bởi pháp luật **nước Cộng hòa Xã hội Chủ nghĩa Việt Nam**. Khi có khác biệt giữa bản Tiếng Việt và Tiếng Anh, bản **Tiếng Việt** có giá trị ưu tiên áp dụng.

---

## 9. Liên hệ & Báo cáo / Contact

- **Nhà phát triển / Developer:** Nguyễn Thế Ngọc (`muitenvn`)
- **Email:** [cloudplayxbox@gmail.com](mailto:cloudplayxbox@gmail.com)
- **GitHub Issues:** [https://github.com/muitenvn/Ravno-App/issues](https://github.com/muitenvn/Ravno-App/issues)
- **Bảo mật / Security Policy:** [SECURITY.md](SECURITY.md)
