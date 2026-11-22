# Ravno — Xbox Cloud Gaming Client for Android & Android TV

**Ravno** là ứng dụng Android độc lập, gọn nhẹ giúp chơi **Xbox Cloud Gaming** (`xbox.com/play`) mượt mà trên **điện thoại, máy tính bảng và Android TV**. Dự án đi kèm **Ravno Pad** — ứng dụng biến điện thoại thành tay cầm không dây độ trễ thấp cho TV qua mạng Wi-Fi nội bộ (LAN). Lấy cảm hứng từ dự án mã nguồn mở [better-xcloud](https://github.com/redphx/better-xcloud).

**Ravno** is a lightweight, independent Android client for **Xbox Cloud Gaming** (`xbox.com/play`) on **phones, tablets, and Android TV**. It includes **Ravno Pad**, a companion app that turns your phone into a low-latency wireless controller for Android TV over local Wi-Fi (LAN). Inspired by the open-source [better-xcloud](https://github.com/redphx/better-xcloud) project.

- **Trang chủ & Tải APK / Website & Download:** [https://muitenvn.github.io/Ravno-App/](https://muitenvn.github.io/Ravno-App/)
- **Bản phát hành / GitHub Releases:** [Tải APK mới nhất / Latest Releases](https://github.com/muitenvn/Ravno-App/releases)
- **Điều khoản & Quyền riêng tư / Terms & Privacy:** [https://muitenvn.github.io/Ravno-App/terms.html](https://muitenvn.github.io/Ravno-App/terms.html)
- **Chính sách Bảo mật / Security Policy:** [SECURITY.md](SECURITY.md)
- **Nhật ký thay đổi / Changelog:** [CHANGELOG.md](CHANGELOG.md)

---

## Tải và Cài đặt / Download & Install

Vào trang [GitHub Releases](https://github.com/muitenvn/Ravno-App/releases) và chọn đúng tệp APK cho thiết bị của bạn:

| Gói cài đặt / Package | Dành cho / For | Mô tả ngắn / Summary |
| :--- | :--- | :--- |
| `CloudPlay-Xbox-v*.apk` | Điện thoại, Máy tính bảng, **Android TV** | Ứng dụng **Ravno** chính để chơi Xbox Cloud Gaming. |
| `CloudPlay-Pad-v*.apk` | Điện thoại Android (tùy chọn) | Ứng dụng **Ravno Pad** biến điện thoại thành tay cầm không dây cho Ravno trên TV. |

> Mỗi bản phát hành đều kèm tệp kiểm tra `.sha256`. Bộ cập nhật tích hợp trong app tự động kiểm tra mã băm SHA-256 trước khi cài đặt. / *Every release includes `.sha256` checksums, verified automatically by the in-app updater.*

---

## Tính năng nổi bật / Key Features

- **Điện thoại, Máy tính bảng & Android TV:** Hỗ trợ giao diện cảm ứng cho di động và giao diện tối ưu màn hình lớn cho Android TV (điều khiển hoàn toàn bằng D-Pad hoặc tay cầm rời). / *Supports mobile touch UI, tablets, and a dedicated Android TV layout with full D-Pad & gamepad navigation.*
- **Ravno Pad — Tay cầm không dây qua Wi-Fi/LAN:** Kết nối điện thoại với Ravno trên Android TV qua NSD (`_cloudplaypad._tcp`), xác thực PIN 4 số + HMAC, truyền nút bấm qua UDP độ trễ thấp, hỗ trợ rung phản hồi và tự nhả nút khi mất kết nối (~450 ms). / *Turns your phone into a wireless LAN gamepad for Android TV with 4-digit PIN + HMAC pairing, low-latency UDP input, rumble feedback, and a 450 ms safety release watchdog.*
- **Tay cầm cảm ứng, Tay cầm rời & Con quay hồi chuyển (Gyroscope):** Đầy đủ nút bấm Xbox tiêu chuẩn 1:1, kéo thả vị trí, chỉnh kích thước/độ mờ và lưu bố cục riêng; tương thích tay cầm Bluetooth/USB-C; tùy chọn ngắm bằng cảm biến con quay hồi chuyển (mặc định tắt). / *Customizable 1:1 Xbox touch controls, Bluetooth/USB-C controller support, and optional on-device gyroscope aiming (OFF by default).*
- **Tối ưu luồng phát & Thông số WebRTC thật:** Chỉnh độ phân giải mục tiêu (720p/1080p), giới hạn bitrate, bộ lọc độ nét/màu sắc, khuếch đại âm lượng, quay màn hình `.mp4` và hiển thị FPS, RTT ping, jitter, mất gói đọc trực tiếp từ WebRTC (`RTCStatsReport`). / *Target 720p/1080p profiles, bitrate cap, clarity/color filters, audio boost, `.mp4` screen recording, and real-time WebRTC telemetry (never fabricated).*
- **Hỗ trợ 12 ngôn ngữ & Dịch giao diện Xbox:** Toàn bộ giao diện Ravno và Ravno Pad hỗ trợ 12 ngôn ngữ (*Tiếng Việt, English, Español, Português, Français, Deutsch, 日本語, 한국어, 中文, Bahasa Indonesia, ไทย, Русский*), kèm tùy chọn dịch menu web Xbox. / *Full 12-language UI across both Ravno and Ravno Pad, plus optional Xbox menu localization.*
- **Công cụ mạng tùy chọn & Bảo vệ tài khoản:** Đo độ trễ thực tế, hỗ trợ định tuyến proxy/VPN cục bộ theo ứng dụng (do người dùng tự chọn) và tự động ngắt kết nối trung gian khi mở trang đăng nhập hoặc thanh toán của Microsoft. / *Real latency probes, optional per-app proxy/VPN routing, and automatic bypass on Microsoft sign-in and billing pages.*
- **Thư viện game & Lịch sử phiên chơi:** Tìm kiếm nhanh danh mục Xbox Cloud, lưu game yêu thích và xem thống kê mạng của các phiên chơi gần đây. / *Fast cloud game library search, favorites, and recent session network stats.*

---

## An toàn & Quyền riêng tư / Safety & Privacy

Chi tiết đầy đủ xem tại [SECURITY.md](SECURITY.md) và [Trang Điều khoản & Quyền riêng tư](https://muitenvn.github.io/Ravno-App/terms.html):

1. **Đăng nhập chính chủ Microsoft:** Tài khoản đăng nhập trực tiếp trên `login.live.com` / `xbox.com` qua HTTPS. Ứng dụng không đọc, không ghi log và không lưu mật khẩu hay mã OTP/2FA.
2. **Bảo vệ trang thanh toán:** Tự động ngắt proxy/VPN trung gian khi mở các trang đăng nhập, thanh toán, mua hàng hoặc nạp mã Microsoft.
3. **Dữ liệu lưu cục bộ, không theo dõi:** Mọi cài đặt, bố cục tay cầm, game yêu thích và lịch sử phiên chơi chỉ lưu trên máy người dùng. Không máy chủ thu thập dữ liệu, không quảng cáo, không SDK theo dõi.
4. **Chơi công bằng (Fair Play):** Chỉ hỗ trợ thao tác tay cầm tiêu chuẩn 1:1; không có cheat, macro, turbo fire hay can thiệp trò chơi.

---

## Cấu trúc dự án / Project Modules

- `:app` (`com.aistudio.cloudplay.kbtolt`) — Ứng dụng **Ravno** chính (Kotlin + Jetpack Compose + WebView) cho điện thoại, máy tính bảng và Android TV.
- `:pad` (`com.muitenvn.cloudplay.pad`) — Ứng dụng tay cầm không dây **Ravno Pad** qua mạng LAN.
- `:remote-protocol` — Thư viện giao thức dùng chung (NSD, ghép đôi PIN/HMAC, khung điều khiển TCP và gói dữ liệu UDP).

---

## Lưu ý pháp lý, Ghi công & Liên hệ / Legal Notice & Contact

- **Dự án độc lập:** Ravno và Ravno Pad là dự án cá nhân độc lập, **không** liên kết, không thuộc sở hữu và không được Microsoft Corporation, Xbox hay Epic Games bảo trợ hoặc ủy quyền. Mọi nhãn hiệu thuộc quyền sở hữu của chủ sở hữu tương ứng.
- **Ghi công / Attribution:**
  - Định vị vị trí IP sử dụng cơ sở dữ liệu miễn phí từ [DB-IP.com](https://db-ip.com) theo giấy phép **CC BY 4.0**.
  - Cảm hứng kỹ thuật và từ điển bản địa hóa menu từ dự án mã nguồn mở [better-xcloud](https://github.com/redphx/better-xcloud) (MIT License).
- **Nhà phát triển / Developer:** Nguyễn Thế Ngọc (`muitenvn`) — Email: [cloudplayxbox@gmail.com](mailto:cloudplayxbox@gmail.com)
