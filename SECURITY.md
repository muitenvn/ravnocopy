# Chính sách Bảo mật & Quyền riêng tư / Security & Privacy Policy

Tài liệu này mô tả minh bạch cách **Ravno** (`com.aistudio.cloudplay.kbtolt`) và ứng dụng tay cầm đi kèm **Ravno Pad** (`com.muitenvn.cloudplay.pad`) bảo vệ tài khoản, kết nối mạng, dữ liệu thiết bị của bạn và quy trình báo cáo lỗ hổng bảo mật.

This document transparently explains how **Ravno** (`com.aistudio.cloudplay.kbtolt`) and its companion controller app **Ravno Pad** (`com.muitenvn.cloudplay.pad`) protect your account, network connections, and device data, and how to report security vulnerabilities.

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

### 2.3. Bảo mật kết nối mạng nội bộ (LAN) cho Ravno Pad / Ravno Pad LAN Security
- **Chỉ hoạt động trong mạng LAN nội bộ:** Ravno trên TV và Ravno Pad trên điện thoại tìm nhau qua NSD/mDNS (`_cloudplaypad._tcp`) và trao đổi tín hiệu tay cầm (TCP điều khiển + UDP nút bấm) trực tiếp trong cùng mạng Wi-Fi gia đình, **không** đi qua máy chủ Internet trung gian.
- **Ghép đôi PIN 4 số + HMAC & chống dò mã:** Ghép đôi yêu cầu mã PIN 4 số hiển thị trên TV, xác thực bằng chữ ký HMAC và mã phiên ngẫu nhiên (16 byte). Nhập sai quá **3 lần** sẽ tự động khóa ghép đôi trong **3 phút**.
- **An toàn kẹt nút (Watchdog ~450 ms):** Nếu đứt mạng hoặc không nhận gói tín hiệu sau khoảng 450 ms, TV tự động nhả toàn bộ nút bấm và đưa cần điều khiển về trung tính.
- **Không ghi log dữ liệu nhạy cảm & không truyền mật khẩu:** PIN, session token và khóa xác thực không bao giờ bị ghi ra nhật ký hệ thống. Kênh điều khiển chỉ truyền tín hiệu tay cầm chuẩn, không dùng để nhập mật khẩu qua kênh chưa mã hóa.
- **LAN-Only Pairing & Safeguards:** Ravno Pad communicates strictly within your local Wi-Fi network (`_cloudplaypad._tcp`), protected by 4-digit PIN + HMAC challenge-response, a 3-minute lockout after 3 failed attempts, 16-byte session tokens, a ~450 ms auto-release watchdog, and zero logging of PINs or tokens.

### 2.4. Toàn vẹn bản cập nhật (SHA-256) / Update Integrity
- **Xác thực mã băm SHA-256:** Bộ kiểm tra cập nhật (`UpdateChecker`) chỉ lấy siêu dữ liệu từ kho chính thức `muitenvn/Ravno-App`. Ngay cả khi tải tệp APK qua máy chủ phụ (mirror CDN), ứng dụng luôn kiểm tra cấu trúc tệp và đối chiếu mã băm **SHA-256** chính thức từ GitHub trước khi mở trình cài đặt Android.
- **SHA-256 Verification:** Even when downloading APKs via CDN mirrors, `UpdateChecker` verifies file integrity against the official GitHub `.sha256` checksum before invoking the Android installer.

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
