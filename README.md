# Ravno — Xbox Cloud Gaming Client for Android & Android TV

**Ravno** là ứng dụng Android độc lập, gọn nhẹ giúp chơi **Xbox Cloud Gaming** (`xbox.com/play`) mượt mà trên **điện thoại, máy tính bảng và Android TV**. Dự án đi kèm **Ravno Pad** — ứng dụng biến điện thoại thành tay cầm không dây cho TV (mặc định qua Wi-Fi/LAN nội bộ, tùy chọn nâng cao kết nối khác Wi-Fi / 4G/5G có mã hóa đầu-cuối, không cần đăng nhập Google). Lấy cảm hứng từ dự án mã nguồn mở [better-xcloud](https://github.com/redphx/better-xcloud).

**Ravno** is a lightweight, independent Android client for **Xbox Cloud Gaming** (`xbox.com/play`) on **phones, tablets, and Android TV**. It includes **Ravno Pad**, a companion app that turns your phone into a wireless controller for Android TV (local Wi-Fi/LAN by default, plus an opt-in end-to-end encrypted off-LAN mode over 4G/5G or different Wi-Fi networks with no Google Sign-In required). Inspired by the open-source [better-xcloud](https://github.com/redphx/better-xcloud) project.

- **Trang chủ & Tải APK / Website & Download:** [https://muitenvn.github.io/Ravno-App/](https://muitenvn.github.io/Ravno-App/)
- **Bản phát hành / GitHub Releases:** [Tải APK mới nhất / Latest Releases](https://github.com/muitenvn/Ravno-App/releases)
- **Điều khoản Người dùng & Quyền riêng tư / Terms of Use & Privacy:** [TERMS.md](TERMS.md) · [Bản web / Web Version](https://muitenvn.github.io/Ravno-App/terms.html)
- **Chính sách Bảo mật / Security Policy:** [SECURITY.md](SECURITY.md)
- **Nhật ký thay đổi / Changelog:** [CHANGELOG.md](CHANGELOG.md)

---

## Tải và Cài đặt / Download & Install

Vào trang [GitHub Releases](https://github.com/muitenvn/Ravno-App/releases) và chọn đúng tệp APK cho thiết bị của bạn:

| Gói cài đặt / Package | Dành cho / For | Mô tả ngắn / Summary |
| :--- | :--- | :--- |
| `CloudPlay-Xbox-v*.apk` | Điện thoại, Máy tính bảng, **Android TV** | Ứng dụng **Ravno** chính để chơi Xbox Cloud Gaming. |
| `CloudPlay-Pad-v*.apk` | Điện thoại Android (tùy chọn) | Ứng dụng **Ravno Pad** biến điện thoại thành tay cầm không dây cho Ravno trên TV. |

> **Cập nhật tức thì & Xác thực SHA-256:** Cả **Ravno** và **Ravno Pad** đều tự động kiểm tra bản phát hành mới từ `muitenvn/Ravno-App` ngay mỗi lần mở ứng dụng (không cần chờ 24 giờ, dùng ETag tiết kiệm băng thông). **Ravno Pad** hỗ trợ tự động tải tệp APK mới qua chuỗi máy chủ phụ / GitHub gốc và luôn xác thực mã băm **SHA-256** trước khi mở trình cài đặt Android. / *Both **Ravno** and **Ravno Pad** automatically check for updates on every app launch (no 24-hour wait, ETag-cached), with automatic verified SHA-256 APK downloads.*

---

## Tính năng nổi bật / Key Features

- **Điện thoại, Máy tính bảng & Android TV:** Hỗ trợ giao diện cảm ứng cho di động và giao diện tối ưu màn hình lớn cho Android TV (điều khiển hoàn toàn bằng D-Pad hoặc tay cầm rời). / *Supports mobile touch UI, tablets, and a dedicated Android TV layout with full D-Pad & gamepad navigation.*
- **Ravno Pad — Tay cầm không dây (LAN mặc định + Khác Wi-Fi tùy chọn):**
  - **Cùng Wi-Fi (LAN — Mặc định):** Tự tìm TV qua NSD (`_cloudplaypad._tcp`), ghép đôi bằng mã PIN 4 số + HMAC, truyền nút bấm qua UDP độ trễ thấp, hỗ trợ rung phản hồi và tự nhả nút sau ~450 ms nếu đứt mạng.
  - **Khác Wi-Fi / 4G/5G (Nâng cao — Mặc định tắt):** Bật trong *Cài đặt → Chế độ nâng cao* hoặc chuyển trực tiếp ngay tại màn ghép đôi trên TV để lấy mã 10 chữ số (`XXXXX-XXXXX`, chỉ cần nhập số trên bàn phím số), nhập ở mục *Nhập IP → Nâng cao (Mã)* trên Ravno Pad. Kênh truyền mã hóa đầu-cuối (TLS 8883 + PBKDF2 60.000 vòng + ECDH P-256 + AES-256-GCM), tự thử nâng cấp về LAN trực tiếp nếu cùng mạng nội bộ, tự hủy mã sau 15 phút rảnh, khóa 3 phút nếu sai 3 lần, tự nhả nút sau 800 ms, và **không dùng Firebase / không cần đăng nhập Google**.
- **Tự kiểm tra cập nhật mỗi lần mở app & Tự động tải trên Ravno Pad:** Không chờ 24 giờ; tự động đối chiếu cấu trúc APK, tên gói và mã băm SHA-256 chính thức từ GitHub trước khi cài đặt. / *Checks official GitHub Releases on every launch (no 24h cooldown) with automatic mirror failover and strict SHA-256 verification on both Ravno and Ravno Pad.*
- **Tay cầm cảm ứng, Tay cầm rời & Con quay hồi chuyển (Gyroscope):** Đầy đủ nút bấm Xbox tiêu chuẩn 1:1, kéo thả vị trí, chỉnh kích thước/độ mờ và lưu bố cục riêng; tương thích tay cầm Bluetooth/USB-C; tùy chọn ngắm bằng cảm biến con quay hồi chuyển (mặc định tắt). / *Customizable 1:1 Xbox touch controls, Bluetooth/USB-C controller support, and optional on-device gyroscope aiming (OFF by default).*
- **Tối ưu luồng phát & Thông số WebRTC thật:** Chỉnh độ phân giải mục tiêu (720p/1080p), giới hạn bitrate, bộ lọc độ nét/màu sắc, khuếch đại âm lượng, quay màn hình `.mp4` và hiển thị FPS, RTT ping, jitter, mất gói đọc trực tiếp từ WebRTC (`RTCStatsReport`). / *Target 720p/1080p profiles, bitrate cap, clarity/color filters, audio boost, `.mp4` screen recording, and real-time WebRTC telemetry (never fabricated).*
- **Hỗ trợ 12 ngôn ngữ & Dịch giao diện Xbox:** Toàn bộ giao diện Ravno và Ravno Pad hỗ trợ 12 ngôn ngữ (*Tiếng Việt, English, Español, Português, Français, Deutsch, 日本語, 한국어, 中文, Bahasa Indonesia, ไทย, Русский*), kèm tùy chọn dịch menu web Xbox. / *Full 12-language UI across both Ravno and Ravno Pad, plus optional Xbox menu localization.*
- **Công cụ mạng tùy chọn & Bảo vệ tài khoản:** Đo độ trễ thực tế, hỗ trợ định tuyến proxy/VPN cục bộ theo ứng dụng (do người dùng tự chọn) và tự động ngắt kết nối trung gian khi mở trang đăng nhập hoặc thanh toán của Microsoft. / *Real latency probes, optional per-app proxy/VPN routing, and automatic bypass on Microsoft sign-in and billing pages.*
- **Thư viện game & Lịch sử phiên chơi:** Tìm kiếm nhanh danh mục Xbox Cloud, lưu game yêu thích và xem thống kê mạng của các phiên chơi gần đây. / *Fast cloud game library search, favorites, and recent session network stats.*

---

## An toàn, Điều khoản & Quyền riêng tư / Safety, Terms & Privacy

Chi tiết đầy đủ xem tại [SECURITY.md](SECURITY.md), [TERMS.md](TERMS.md) và [Trang Điều khoản & Quyền riêng tư](https://muitenvn.github.io/Ravno-App/terms.html):

1. **Đăng nhập chính chủ Microsoft:** Tài khoản đăng nhập trực tiếp trên `login.live.com` / `xbox.com` qua HTTPS. Ứng dụng không đọc, không ghi log và không lưu mật khẩu hay mã OTP/2FA.
2. **Bảo vệ trang thanh toán:** Tự động ngắt proxy/VPN trung gian khi mở các trang đăng nhập, thanh toán, mua hàng hoặc nạp mã Microsoft.
3. **Dữ liệu lưu cục bộ, không theo dõi:** Mọi cài đặt, bố cục tay cầm, game yêu thích và lịch sử phiên chơi chỉ lưu trên máy người dùng. Không máy chủ thu thập dữ liệu, không quảng cáo, không SDK theo dõi, không ép đăng nhập Google.
4. **Chơi công bằng (Fair Play):** Chỉ hỗ trợ thao tác tay cầm tiêu chuẩn 1:1; không có cheat, macro, turbo fire hay can thiệp trò chơi.

---

## Cấu trúc dự án / Project Modules

- `:app` (`com.aistudio.cloudplay.kbtolt`) — Ứng dụng **Ravno** chính (Kotlin + Jetpack Compose + WebView) cho điện thoại, máy tính bảng và Android TV.
- `:pad` (`com.muitenvn.cloudplay.pad`) — Ứng dụng tay cầm không dây **Ravno Pad** (LAN mặc định, tùy chọn khác Wi-Fi mã hóa đầu-cuối, tự kiểm tra & tải cập nhật SHA-256).
- `:remote-protocol` — Thư viện giao thức dùng chung (NSD, ghép đôi PIN/HMAC, khung điều khiển TCP, gói UDP, mã hóa ECDH P-256 / AES-256-GCM và client MQTT TLS 8883 thuần Kotlin).

---

## Lưu ý pháp lý, Ghi công & Liên hệ / Legal Notice & Contact

- **Dự án độc lập:** Ravno và Ravno Pad là dự án cá nhân độc lập, **không** liên kết, không thuộc sở hữu và không được Microsoft Corporation, Xbox hay Epic Games bảo trợ hoặc ủy quyền. Mọi nhãn hiệu thuộc quyền sở hữu của chủ sở hữu tương ứng.
- **Ghi công / Attribution:**
  - Định vị vị trí IP sử dụng cơ sở dữ liệu miễn phí từ [DB-IP.com](https://db-ip.com) theo giấy phép **CC BY 4.0**.
  - Cảm hứng kỹ thuật và từ điển bản địa hóa menu từ dự án mã nguồn mở [better-xcloud](https://github.com/redphx/better-xcloud) (MIT License).
- **Nhà phát triển / Developer:** Nguyễn Thế Ngọc (`muitenvn`) — Email: [cloudplayxbox@gmail.com](mailto:cloudplayxbox@gmail.com)
