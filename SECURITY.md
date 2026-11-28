# Chính sách Bảo mật & Quyền riêng tư / Security & Privacy Policy

Tài liệu này mô tả minh bạch cách **Ravno** (`com.aistudio.cloudplay.kbtolt`) và ứng dụng tay cầm đi kèm **Ravno Pad** (`com.muitenvn.cloudplay.pad`) bảo vệ tài khoản, kết nối mạng, dữ liệu thiết bị của bạn và quy trình báo cáo lỗ hổng bảo mật. Xem thêm Điều khoản người dùng đầy đủ tại [TERMS.md](TERMS.md).

This document transparently explains how **Ravno** (`com.aistudio.cloudplay.kbtolt`) and its companion controller app **Ravno Pad** (`com.muitenvn.cloudplay.pad`) protect your account, network connections, and device data, and how to report security vulnerabilities. See [TERMS.md](TERMS.md) for the full Terms of Use.

---

## 1. Phiên bản được hỗ trợ / Supported Versions

Để đảm bảo an toàn, vui lòng luôn cập nhật lên bản phát hành mới nhất từ [GitHub Releases](https://github.com/muitenvn/Ravno-App/releases):

| Phiên bản / Version | Cập nhật bảo mật / Security Updates |
| :--- | :--- |
| Bản phát hành mới nhất (Latest release — Ravno & Ravno Pad) | :white_check_mark: Có / Yes |
| Các phiên bản cũ hơn (Older versions) | :x: Không / No |

---

## 2. Cơ chế Bảo vệ Người dùng / Security Safeguards

### 2.1. An toàn tài khoản Microsoft & Xbox / Account Safety
- **Đăng nhập chính chủ qua HTTPS:** Quá trình đăng nhập diễn ra trực tiếp giữa hệ thống `WebView` trên máy bạn và trang chính thức của Microsoft (`login.live.com`, `login.microsoftonline.com`, `xbox.com`). Cả hai ứng dụng đều tắt lưu lượng HTTP không mã hóa (`usesCleartextTraffic="false"`).
- **Không thu thập mật khẩu hay mã xác thực:** Mã nguồn không đọc, không ghi nhật ký và không lưu mật khẩu, mã OTP/2FA hoặc thông tin thẻ thanh toán.
- **Official HTTPS Sign-In Only:** You sign in directly on official Microsoft pages (`login.live.com`, `login.microsoftonline.com`, `xbox.com`). Both apps enforce `usesCleartextTraffic="false"` and never read, log, or store passwords, 2FA/OTP codes, or payment credentials.

### 2.2. Tự động bảo vệ khi đăng nhập & thanh toán / Login & Billing Protection
- **Tự động ngắt máy chủ trung gian:** Nếu bạn bật tính năng proxy/VPN tùy chọn (chỉ cô lập trong phạm vi riêng ứng dụng Ravno), ứng dụng sẽ tự động ngắt kết nối trung gian hoặc chuyển sang trình duyệt mặc định khi phát hiện các trang đăng nhập, mua hàng, nạp mã hoặc quản lý tài khoản của Microsoft.
- **Automatic Relay Bypass:** When optional per-app proxy/VPN routing is active, Ravno automatically suspends the relay or opens the system browser whenever you navigate to Microsoft sign-in, billing, checkout, redeem, or account pages.

### 2.3. Bảo mật kết nối Ravno Pad (LAN mặc định & Khác Wi-Fi mã hóa đầu-cuối) / Ravno Pad Security
- **Chế độ mặc định — Mạng nội bộ LAN (không ra Internet):** Ravno trên TV và Ravno Pad trên điện thoại tìm nhau qua NSD/mDNS (`_cloudplaypad._tcp`), xác thực bằng **PIN 4 số + HMAC-SHA256**, cấp mã phiên ngẫu nhiên 16 byte, khóa tạm thời **3 phút** sau **3 lần nhập sai**, và tự nhả toàn bộ nút bấm sau khoảng **450 ms** nếu đứt mạng hoặc khi ứng dụng vào nền.
- **Chế độ nâng cao (tùy chọn, mặc định TẮT) — Kết nối khác Wi-Fi / 4G/5G mã hóa đầu-cuối:**
  - **Xác nhận chủ động & không dùng tài khoản Google / Firebase:** Tính năng mặc định tắt hoàn toàn; người dùng phải chủ động bật và xác nhận trên TV mới sinh mã 10 chữ số ngẫu nhiên (`XXXXX-XXXXX`, chỉ gồm số `0–9` để nhập nhanh trên bàn phím số).
  - **Mã hóa 2 lớp (TLS 8883 + E2EE AES-256-GCM):** Gói tin đi qua broker trung gian công cộng có mã hóa TLS cổng 8883 (`broker.emqx.io` chính / `broker.hivemq.com` dự phòng, xác thực chứng chỉ HTTPS + SNI, QoS 0, không lưu lại `retain=false`). Khóa bắt tay và tên chủ đề ghép đôi ẩn danh đều dẫn xuất từ `PBKDF2WithHmacSHA256` (60.000 vòng) nhằm chống dò mã ngoại tuyến, kết hợp trao đổi khóa **ECDH P-256** tạm thời + **HKDF-SHA256** để tạo khóa phiên **AES-256-GCM** riêng cho từng phiên chơi, sau đó chuyển sang chủ đề phiên ngẫu nhiên mới. Máy chủ trung gian chỉ thấy dữ liệu đã mã hóa, **không thể đọc hay giả mạo** tín hiệu tay cầm.
  - **Ưu tiên nâng cấp LAN trực tiếp (~650 ms):** Nếu điện thoại và TV thực tế ở cùng mạng nội bộ, hai máy dùng mã xác thực nhanh dùng một lần (hết hạn sau 15 giây, `AUTH_QUICK`) để tự chuyển thẳng về kênh TCP/UDP LAN rồi đóng kết nối trung gian.
  - **Chống dò mã & An toàn kẹt nút:** Mã 10 chữ số tự hết hạn sau **15 phút** không có kết nối; nhập sai **3 lần** tự khóa **3 phút**; bộ giám sát WAN (**watchdog 800 ms**) tự nhả sạch toàn bộ nút bấm khi mất kết nối, xoay màn hình hoặc đưa ứng dụng vào nền.
- **Không ghi log dữ liệu nhạy cảm:** PIN 4 số, mã 10 chữ số, khóa ECDH/AES, mã nhanh và session token chỉ nằm trong bộ nhớ RAM (hoặc token LAN cục bộ), tuyệt đối **không** ghi ra nhật ký hệ thống hay gửi ra ngoài.
- **Summary (EN):** Ravno Pad defaults to local LAN only (4-digit PIN + HMAC, 3-fail 3-min lockout, 450 ms watchdog). The opt-in off-LAN mode (OFF by default, confirmation required, zero Firebase/Google Sign-In) uses a 10-digit numeric code (`XXXXX-XXXXX`) over TLS port 8883 (`broker.emqx.io` / `broker.hivemq.com`) with 60,000-iteration PBKDF2-HMAC-SHA256 key & topic derivation, ephemeral ECDH P-256 + HKDF-SHA256 + AES-256-GCM end-to-end encryption, automatic direct LAN upgrade, 15-minute idle code expiry, 3-fail 3-min lockout, 800 ms WAN watchdog, and zero logging of codes or keys.

### 2.4. Toàn vẹn bản cập nhật (SHA-256) trên cả Ravno & Ravno Pad / Update Integrity
- **Kiểm tra mỗi lần mở app (không chờ 24 giờ):** Cả `UpdateChecker` (Ravno) và `PadUpdateChecker` (Ravno Pad) chỉ kiểm tra siêu dữ liệu từ kho chính thức `muitenvn/Ravno-App` qua HTTPS (dùng `If-None-Match` ETag và dự phòng chuyển hướng HTTPS trực tiếp, không đi qua proxy/VPN của app).
- **Xác thực bắt buộc trước khi cài đặt:** Ngay cả khi tự động tải tệp APK qua chuỗi máy chủ phụ (mirror CDN), cả hai ứng dụng đều giới hạn danh sách tên miền HTTPS được phép, kiểm tra chữ ký tệp ZIP (`PK\x03\x04`), kiểm tra kích thước hợp lệ, kiểm tra đúng `packageName` và đối chiếu mã băm **SHA-256** chính thức từ GitHub trước khi mở trình cài đặt hệ thống qua `FileProvider`.
- **SHA-256 Verification (EN):** Both Ravno and Ravno Pad check official GitHub Releases on every app launch (ETag-cached, no 24h wait) and validate HTTPS allowlists, ZIP headers, package identity, and official `.sha256` digests before invoking the Android installer.

### 2.5. Dữ liệu cục bộ, Cảm biến & Chơi công bằng / Local-Only Data, Sensors & Fair Play
- **Không máy chủ thu thập dữ liệu, không quảng cáo:** Toàn bộ cài đặt, bố cục tay cầm, danh sách thiết bị đã ghép đôi, game yêu thích, lịch sử chơi và nhật ký kỹ thuật chỉ lưu trong bộ nhớ riêng trên thiết bị của bạn. Bạn có thể xóa phiên đăng nhập, nhật ký, lịch sử hoặc toàn bộ dữ liệu bất kỳ lúc nào.
- **Cảm biến con quay hồi chuyển (Gyroscope):** Mặc định **tắt**; khi bật, dữ liệu cảm biến chỉ xử lý trực tiếp trên máy để điều khiển hướng ngắm và tự động dừng khi app vào nền.
- **Chơi công bằng (Fair Play):** Chỉ cung cấp ánh xạ nút bấm Xbox tiêu chuẩn 1:1 và tùy chỉnh hiển thị; không có tính năng gian lận (cheat), macro/turbo tự động hay can thiệp bộ nhớ trò chơi.
- **Local-Only & Fair Play:** Zero telemetry servers, zero ads, gyroscope OFF by default (on-device only), and strict 1:1 standard controller inputs with no cheats or macros.

---

## 3. Cách báo cáo lỗ hổng bảo mật / Reporting a Vulnerability

Nếu bạn phát hiện lỗi bảo mật có thể ảnh hưởng đến an toàn tài khoản, quyền riêng tư hoặc kết nối mạng của người dùng, vui lòng **không** đăng chi tiết khai thác công khai lên Issues hoặc mạng xã hội. Hãy gửi báo cáo riêng tư theo một trong hai cách sau:

If you discover a security vulnerability affecting user accounts, privacy, or network safety, please **do not** disclose exploit details publicly. Report it privately through one of the following channels:

1. **GitHub Security Advisories (Khuyên dùng / Recommended):** Mở thẻ **Security** trên kho `muitenvn/Ravno-App` → chọn **Report a vulnerability**.
2. **Email nhà phát triển / Developer Email:** Gửi mô tả lỗi, phiên bản ứng dụng và các bước tái hiện tới [cloudplayxbox@gmail.com](mailto:cloudplayxbox@gmail.com).

**Cam kết phản hồi / Response Timeline:**
- Xác nhận tiếp nhận trong vòng **48 giờ** (*Acknowledgement within 48 hours*).
- Kiểm tra, phối hợp khắc phục và phát hành bản vá kèm thông báo công khai sau khi lỗi đã được sửa (*Fix verification and coordinated disclosure after patching*).
