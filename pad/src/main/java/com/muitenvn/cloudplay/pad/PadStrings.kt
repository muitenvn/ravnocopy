package com.muitenvn.cloudplay.pad

import android.content.res.Resources
import android.os.Build
import java.util.Locale

/**
 * Hỗ trợ đầy đủ 12 ngôn ngữ như CloudPlay hiện có:
 * Tiếng Việt (vi), English (en), Español (es), Português (pt), Français (fr),
 * Deutsch (de), 日本語 (ja), 한국어 (ko), 中文 (zh), Bahasa Indonesia (id), ไทย (th), Русский (ru).
 */
enum class PadLanguage(
    val code: String,
    val badge: String,
    val displayName: String
) {
    SYSTEM("system", "AUTO", "System"),
    VIETNAMESE("vi", "VI", "Tiếng Việt"),
    ENGLISH("en", "EN", "English"),
    SPANISH("es", "ES", "Español"),
    PORTUGUESE("pt", "PT", "Português"),
    FRENCH("fr", "FR", "Français"),
    GERMAN("de", "DE", "Deutsch"),
    JAPANESE("ja", "JA", "日本語"),
    KOREAN("ko", "KO", "한국어"),
    CHINESE("zh", "ZH", "中文"),
    INDONESIAN("id", "ID", "Indonesia"),
    THAI("th", "TH", "ไทย"),
    RUSSIAN("ru", "RU", "Русский");

    fun effectiveCode(): String {
        if (this != SYSTEM) return code
        val sys = try {
            val conf = Resources.getSystem().configuration
            val loc = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N && !conf.locales.isEmpty) {
                conf.locales[0]
            } else {
                @Suppress("DEPRECATION")
                conf.locale
            }
            loc?.language?.lowercase(Locale.ROOT).orEmpty()
        } catch (_: Exception) {
            Locale.getDefault().language.lowercase(Locale.ROOT)
        }
        val norm = if (sys == "in") "id" else sys
        return entries.firstOrNull { it != SYSTEM && norm.startsWith(it.code) }?.code ?: "en"
    }
}

object PadStrings {
    fun tr(
        lang: PadLanguage,
        vi: String,
        en: String,
        es: String = en,
        pt: String = en,
        fr: String = en,
        de: String = en,
        ja: String = en,
        ko: String = en,
        zh: String = en,
        id: String = en,
        th: String = en,
        ru: String = en
    ): String {
        return when (lang.effectiveCode()) {
            "vi" -> vi
            "es" -> es
            "pt" -> pt
            "fr" -> fr
            "de" -> de
            "ja" -> ja
            "ko" -> ko
            "zh" -> zh
            "id" -> id
            "th" -> th
            "ru" -> ru
            else -> en
        }
    }

    fun selectTvTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Chọn TV để kết nối",
        en = "Select a TV to Connect",
        es = "Selecciona un TV para conectar",
        pt = "Selecione uma TV para conectar",
        fr = "Sélectionner un téléviseur",
        de = "Fernseher zum Verbinden wählen",
        ja = "接続するテレビを選択",
        ko = "연결할 TV 선택",
        zh = "选择要连接的电视",
        id = "Pilih TV untuk dihubungkan",
        th = "เลือกทีวีที่จะเชื่อมต่อ",
        ru = "Выберите ТВ для подключения"
    )

    fun selectTvGuide(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Mở Ravno trên TV → Cài đặt → Dùng điện thoại làm tay cầm để lấy mã 4 số.",
        en = "Open Ravno on TV → Settings → Use Phone as Controller to get the 4-digit code.",
        es = "Abre Ravno en el TV → Ajustes → Usar teléfono como mando para obtener el código de 4 dígitos.",
        pt = "Abra o Ravno na TV → Configurações → Usar celular como controle para ver o código de 4 dígitos.",
        fr = "Ouvrez Ravno sur la TV → Paramètres → Téléphone comme manette pour le code à 4 chiffres.",
        de = "Ravno auf TV öffnen → Einstellungen → Smartphone als Controller für den 4-stelligen Code.",
        ja = "テレビでRavno → 設定 → スマホをコントローラーとして使用を開き4桁のコードを確認。",
        ko = "TV에서 Ravno 열기 → 설정 → 휴대폰을 컨트롤러로 사용에서 4자리 코드 확인.",
        zh = "在电视上打开 Ravno → 设置 → 使用手机作为手柄，获取 4 位配对码。",
        id = "Buka Ravno di TV → Pengaturan → Gunakan Ponsel sebagai Stik untuk kode 4 digit.",
        th = "เปิด Ravno บนทีวี → การตั้งค่า → ใช้โทรศัพท์เป็นจอย เพื่อรับรหัส 4 หลัก",
        ru = "Откройте Ravno на ТВ → Настройки → Телефон как геймпад, чтобы получить 4-значный код."
    )

    fun statusReadyPair(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Sẵn sàng ghép đôi · Chờ mã 4 số",
        en = "Ready to pair · Waiting for 4-digit code",
        es = "Listo para vincular · Código de 4 dígitos",
        pt = "Pronto para parear · Código de 4 dígitos",
        fr = "Prêt à jumeler · Code à 4 chiffres",
        de = "Kopplungsbereit · 4-stelliger Code",
        ja = "ペアリング準備完了 · 4桁のコード待ち",
        ko = "페어링 준비 완료 · 4자리 코드 대기",
        zh = "准备配对 · 等待输入 4 位配对码",
        id = "Siap dipasangkan · Menunggu kode 4 digit",
        th = "พร้อมจับคู่ · รอรหัส 4 หลัก",
        ru = "Готов к сопряжению · Код из 4 цифр"
    )

    fun statusRemembered(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đã nhớ thiết bị · Chạm để kết nối ngay",
        en = "Remembered TV · Tap to connect immediately",
        es = "TV recordado · Toca para conectar",
        pt = "TV lembrada · Toque para conectar",
        fr = "TV mémorisée · Appuyez pour connecter",
        de = "Gespeichert · Tippen zum Verbinden",
        ja = "登録済みテレビ · タップしてすぐ接続",
        ko = "기억된 TV · 탭하여 즉시 연결",
        zh = "已记住设备 · 点击立即连接",
        id = "Perangkat tersimpan · Ketuk untuk hubungkan",
        th = "จำอุปกรณ์แล้ว · แตะเพื่อเชื่อมต่อทันที",
        ru = "Устройство сохранено · Нажмите для входа"
    )

    fun statusHasController(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang có tay cầm kết nối",
        en = "Controller currently connected",
        es = "Mando conectado actualmente",
        pt = "Controle conectado no momento",
        fr = "Manette actuellement connectée",
        de = "Controller bereits verbunden",
        ja = "コントローラー接続中",
        ko = "현재 컨트롤러 연결됨",
        zh = "当前已有手柄连接",
        id = "Stik sedang terhubung",
        th = "มีจอยเชื่อมต่ออยู่แล้ว",
        ru = "Геймпад уже подключен"
    )

    fun statusBusy(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang bận · Không nhận thêm tay cầm",
        en = "Busy · Not accepting controllers",
        es = "Ocupado · No acepta más mandos",
        pt = "Ocupado · Não aceita controles",
        fr = "Occupé · N'accepte plus de manettes",
        de = "Beschäftigt · Keine weiteren Controller",
        ja = "ビジー · 接続できません",
        ko = "사용 중 · 추가 연결 불가",
        zh = "繁忙 · 暂不接受新连接",
        id = "Sibuk · Tidak menerima stik baru",
        th = "ไม่ว่าง · ไม่รับจอยเพิ่ม",
        ru = "Занят · Не принимает подключения"
    )

    fun rescanButton(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Quét lại",
        en = "Rescan",
        es = "Reescanear",
        pt = "Buscar novamente",
        fr = "Actualiser",
        de = "Neu suchen",
        ja = "再スキャン",
        ko = "다시 스캔",
        zh = "重新扫描",
        id = "Pindai ulang",
        th = "สแกนใหม่",
        ru = "Обновить"
    )

    fun localOnlyNotice(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Chỉ hoạt động trong mạng nội bộ (LAN)",
        en = "Works on local network (LAN) only",
        es = "Solo funciona en red local (LAN)",
        pt = "Funciona apenas na rede local (LAN)",
        fr = "Réseau local (LAN) uniquement",
        de = "Nur im lokalen Netzwerk (LAN)",
        ja = "ローカルネットワーク(LAN)専用",
        ko = "로컬 네트워크(LAN) 전용",
        zh = "仅在局域网 (LAN) 内运行",
        id = "Hanya bekerja di jaringan lokal (LAN)",
        th = "ทำงานเฉพาะในเครือข่ายภายใน (LAN)",
        ru = "Только в локальной сети (LAN)"
    )

    fun enterPinTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Nhập mã 4 số trên TV",
        en = "Enter 4-Digit Code on TV",
        es = "Ingresa el código de 4 dígitos del TV",
        pt = "Digite o código de 4 dígitos da TV",
        fr = "Entrez le code à 4 chiffres affiché sur la TV",
        de = "4-stelligen Code vom TV eingeben",
        ja = "テレビの4桁のコードを入力",
        ko = "TV에 표시된 4자리 코드 입력",
        zh = "输入电视上的 4 位配对码",
        id = "Masukkan kode 4 digit di TV",
        th = "ป้อนรหัส 4 หลักบนทีวี",
        ru = "Введите 4-значный код с экрана ТВ"
    )

    fun enterPinSubtitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Nhìn lên màn hình TV và nhập 4 chữ số đang hiển thị.",
        en = "Look at your TV screen and enter the 4 digits shown.",
        es = "Mira la pantalla del TV e introduce los 4 dígitos.",
        pt = "Olhe para a tela da TV e digite os 4 números exibidos.",
        fr = "Regardez l'écran du téléviseur et saisissez les 4 chiffres.",
        de = "Gib die 4 Ziffern ein, die auf dem Fernseher angezeigt werden.",
        ja = "テレビ画面に表示されている4桁の数字を入力してください。",
        ko = "TV 화면에 표시된 4자리 숫자를 입력하세요.",
        zh = "请查看电视屏幕并输入显示的 4 位数字。",
        id = "Lihat layar TV Anda dan masukkan 4 angka yang tampil.",
        th = "ดูที่หน้าจอทีวีแล้วป้อนตัวเลข 4 หลักที่แสดงอยู่",
        ru = "Посмотрите на экран ТВ и введите 4 отображаемые цифры."
    )

    fun changeTvBack(lang: PadLanguage) = tr(
        lang = lang,
        vi = "← Đổi TV",
        en = "← Change TV",
        es = "← Cambiar TV",
        pt = "← Trocar TV",
        fr = "← Changer TV",
        de = "← TV wechseln",
        ja = "← テレビ変更",
        ko = "← TV 변경",
        zh = "← 更换电视",
        id = "← Ganti TV",
        th = "← เปลี่ยนทีวี",
        ru = "← Сменить ТВ"
    )

    fun keypadDelete(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Xóa",
        en = "Del",
        es = "Borrar",
        pt = "Apagar",
        fr = "Effacer",
        de = "Löschen",
        ja = "削除",
        ko = "지우기",
        zh = "删除",
        id = "Hapus",
        th = "ลบ",
        ru = "Стереть"
    )

    fun keypadConnect(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Kết nối",
        en = "Connect",
        es = "Conectar",
        pt = "Conectar",
        fr = "Connecter",
        de = "Verbinden",
        ja = "接続",
        ko = "연결",
        zh = "连接",
        id = "Hubungkan",
        th = "เชื่อมต่อ",
        ru = "Войти"
    )

    fun needUpdateCloudPlay(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Cần cập nhật Ravno",
        en = "Need to update Ravno",
        es = "Actualiza Ravno en el TV",
        pt = "Atualize o Ravno na TV",
        fr = "Mise à jour de Ravno requise",
        de = "Ravno muss aktualisiert werden",
        ja = "Ravnoの更新が必要です",
        ko = "Ravno 업데이트 필요",
        zh = "需要更新 Ravno",
        id = "Perlu memperbarui Ravno",
        th = "ต้องอัปเดต Ravno",
        ru = "Требуется обновить Ravno"
    )

    fun needUpdateCloudPlayPad(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Cần cập nhật Ravno Pad",
        en = "Need to update Ravno Pad",
        es = "Actualiza Ravno Pad",
        pt = "Atualize o Ravno Pad",
        fr = "Mise à jour de Ravno Pad requise",
        de = "Ravno Pad muss aktualisiert werden",
        ja = "Ravno Padの更新が必要です",
        ko = "Ravno Pad 업데이트 필요",
        zh = "需要更新 Ravno Pad",
        id = "Perlu memperbarui Ravno Pad",
        th = "ต้องอัปเดต Ravno Pad",
        ru = "Требуется обновить Ravno Pad"
    )

    fun gyroLabel(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Con quay hồi chuyển",
        en = "Gyroscope",
        es = "Giroscopio",
        pt = "Giroscópio",
        fr = "Gyroscope",
        de = "Gyroskop",
        ja = "ジャイロ操作",
        ko = "자이로스코프",
        zh = "陀螺仪",
        id = "Giroskop",
        th = "ไจโรสโคป",
        ru = "Гироскоп"
    )

    fun gyroSectionTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "🧭 CON QUAY HỒI CHUYỂN (THEO GAME / ĐUA XE)",
        en = "🧭 GYROSCOPE (GAME NATIVE / RACING)",
        es = "🧭 GIROSCOPIO (JUEGO NATIVO / CARRERAS)",
        pt = "🧭 GIROSCÓPIO (NATIVO DO JOGO / CORRIDA)",
        fr = "🧭 GYROSCOPE (JEU NATIF / COURSE)",
        de = "🧭 GYROSKOP (SPIEL-NATIV / RENNEN)",
        ja = "🧭 ジャイロ (ゲーム対応 / レース)",
        ko = "🧭 자이로스코프 (게임 지원 / 레이싱)",
        zh = "🧭 陀螺仪 (游戏原生 / 赛车)",
        id = "🧭 GIROSKOP (BAWAAN GAME / BALAPAN)",
        th = "🧭 ไจโรสโคป (ตามเกมรองรับ / แข่งรถ)",
        ru = "🧭 ГИРОСКОП (ПОДДЕРЖКА ИГРЫ / ГОНКИ)"
    )

    fun gyroPhoneTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Bật con quay hồi chuyển",
        en = "Enable Gyroscope",
        es = "Activar giroscopio",
        pt = "Ativar giroscópio",
        fr = "Activer le gyroscope",
        de = "Gyroskop aktivieren",
        ja = "ジャイロを有効にする",
        ko = "자이로스코프 켜기",
        zh = "启用陀螺仪",
        id = "Aktifkan Giroskop",
        th = "เปิดใช้งานไจโรสโคป",
        ru = "Включить гироскоп"
    )

    fun gyroDefaultOffHint(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Mặc định tắt — bật để dùng theo game hỗ trợ hoặc nghiêng lái xe thủ công.",
        en = "Off by default — turn on for game-supported gyro or manual racing tilt.",
        es = "Apagado por defecto — actívalo para giroscopio nativo del juego o giro manual de carreras.",
        pt = "Desativado por padrão — ative para giroscópio nativo do jogo ou direção manual de corrida.",
        fr = "Désactivé par défaut — activez pour le gyroscope natif du jeu ou la direction course manuelle.",
        de = "Standardmäßig aus — aktivieren für Spiel-Gyroskop oder manuelle Renn-Neigung.",
        ja = "初期設定はオフ — ゲーム対応ジャイロまたは手動レース傾き操作に使用。",
        ko = "기본값 꺼짐 — 게임 지원 자이로 또는 수동 레이싱 기울기 조향에 사용.",
        zh = "默认关闭 — 开启后可按游戏原生支持使用，或手动倾斜控制赛车转向。",
        id = "Mati secara default — aktifkan untuk giroskop bawaan game atau miring setir manual.",
        th = "ปิดเป็นค่าเริ่มต้น — เปิดเพื่อใช้ตามเกมที่รองรับ หรือเอียงบังคับรถด้วยตนเอง",
        ru = "По умолчанию выкл. — для нативного гироскопа игры или ручного руля в гонках."
    )

    fun gyroStatusOn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "● Đang bật",
        en = "● Enabled",
        es = "● Activado",
        pt = "● Ativado",
        fr = "● Activé",
        de = "● Ein",
        ja = "● オン",
        ko = "● 켜짐",
        zh = "● 已开启",
        id = "● Aktif",
        th = "● เปิดอยู่",
        ru = "● Вкл."
    )

    fun gyroStatusOffDefault(lang: PadLanguage) = tr(
        lang = lang,
        vi = "○ Đang tắt (Mặc định)",
        en = "○ Off (Default)",
        es = "○ Apagado (Predet.)",
        pt = "○ Desligado (Padrão)",
        fr = "○ Désactivé (Défaut)",
        de = "○ Aus (Standard)",
        ja = "○ オフ (既定)",
        ko = "○ 꺼짐 (기본값)",
        zh = "○ 关闭 (默认)",
        id = "○ Mati (Default)",
        th = "○ ปิด (ค่าเริ่มต้น)",
        ru = "○ Выкл. (по умолч.)"
    )

    fun gyroModeGameNativeTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Theo game hỗ trợ (Mặc định)",
        en = "Game Native Support (Default)",
        es = "Solo si el juego lo soporta (Predet.)",
        pt = "Apenas jogos compatíveis (Padrão)",
        fr = "Selon support du jeu (Défaut)",
        de = "Nur bei Spiel-Unterstützung (Standard)",
        ja = "ゲーム標準対応のみ (既定)",
        ko = "게임 기본 지원 전용 (기본값)",
        zh = "仅当游戏原生支持 (默认)",
        id = "Khusus Game yang Mendukung (Default)",
        th = "เฉพาะเกมที่รองรับ (ค่าเริ่มต้น)",
        ru = "Только если игра поддерживает (По умолч.)"
    )

    fun gyroModeGameNativeShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Game hỗ trợ",
        en = "Game Native",
        es = "Nativo del juego",
        pt = "Nativo do jogo",
        fr = "Jeu natif",
        de = "Spiel-nativ",
        ja = "ゲーム対応",
        ko = "게임 지원",
        zh = "游戏原生",
        id = "Bawaan Game",
        th = "ตามเกมรองรับ",
        ru = "Поддержка игры"
    )

    fun gyroModeRacingTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đua xe — Nghiêng trái/phải (Thủ công)",
        en = "Racing — Left/Right Tilt (Manual)",
        es = "Carreras — Inclinar Izq./Der. (Manual)",
        pt = "Corrida — Inclinar Esq./Dir. (Manual)",
        fr = "Course — Inclinaison G/D (Manuel)",
        de = "Rennen — Links/Rechts neigen (Manuell)",
        ja = "レース — 左右傾き操作 (手動)",
        ko = "레이싱 — 좌/우 기울기 조향 (수동)",
        zh = "赛车 — 左右倾斜转向 (手动)",
        id = "Balapan — Miring Kiri/Kanan (Manual)",
        th = "แข่งรถ — เอียงซ้าย/ขวา (เปิดเอง)",
        ru = "Гонки — Наклон влево/вправо (Вручную)"
    )

    fun gyroModeRacingShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đua xe (Trái/Phải)",
        en = "Racing (Tilt)",
        es = "Carreras (Giro)",
        pt = "Corrida (Volante)",
        fr = "Course (Volant)",
        de = "Rennen (Lenken)",
        ja = "レース (左右)",
        ko = "레이싱 (좌/우)",
        zh = "赛车 (左右倾)",
        id = "Balapan (Setir)",
        th = "แข่งรถ (ซ้าย/ขวา)",
        ru = "Гонки (Руль)"
    )

    fun gyroGameNativePadNotice(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Không giả lập cần tay cầm ở chế độ này. Giao thức tay cầm Xbox qua LAN không có kênh gyro gốc — chọn thủ công Đua xe (Trái/Phải) để nghiêng lái xe.",
        en = "No stick emulation is sent in this mode. Standard Xbox LAN gamepad protocol has no native gyro channel — switch manually to Racing (Tilt) to steer left/right.",
        es = "No se envía emulación de stick en este modo. El protocolo Xbox LAN no tiene canal de giroscopio nativo: cambia manualmente a Carreras (Giro) para girar.",
        pt = "Nenhuma emulação de analógico é enviada neste modo. O protocolo Xbox LAN não tem canal de giroscópio nativo — mude manualmente para Corrida para virar.",
        fr = "Aucune émulation de stick n'est envoyée dans ce mode. Le protocole manette Xbox LAN n'a pas de canal gyroscopique natif — passez manuellement en Course pour tourner.",
        de = "In diesem Modus wird keine Stick-Emulation gesendet. Das Xbox-LAN-Protokoll bietet keinen nativen Gyro-Kanal — für Lenkung manuell zu Rennen wechseln.",
        ja = "このモードではスティック擬似入力を送信しません。標準Xbox LANプロトコルにネイティブジャイロ通信はないため、傾き運転はレースモードへ手動切替してください。",
        ko = "이 모드에서는 스틱 가상 입력을 전송하지 않습니다. 표준 Xbox LAN 게임패드 프로토콜에는 기본 자이로 채널이 없으므로 레이싱 모드로 수동 전환하세요.",
        zh = "此模式不发送摇杆模拟信号。标准 Xbox 局域网手柄协议不含原生陀螺仪通道 — 如需左右倾斜驾驶请手动切换到赛车模式。",
        id = "Tidak ada emulasi stik yang dikirim di mode ini. Protokol stik Xbox LAN tidak memiliki kanal giroskop bawaan — beralih manual ke Balapan untuk setir.",
        th = "โหมดนี้จะไม่ส่งค่าจำลองก้านอนาล็อก โปรโตคอลจอย Xbox LAN ไม่มีช่องไจโรสโคปดั้งเดิม — เปลี่ยนเป็นโหมดแข่งรถด้วยตนเองเพื่อเอียงเลี้ยว",
        ru = "В этом режиме эмуляция стика не отправляется. Стандартный протокол геймпада Xbox LAN не имеет нативного канала гироскопа — выберите Гонки вручную для руления."
    )

    fun gyroSteerSensShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Độ nhạy lái",
        en = "Steer Sens",
        es = "Sens. volante",
        pt = "Sens. volante",
        fr = "Sens. volant",
        de = "Lenkempfindl.",
        ja = "ハンドル感度",
        ko = "조향 감도",
        zh = "转向灵敏度",
        id = "Sens. Setir",
        th = "ความไวพวงมาลัย",
        ru = "Чувств. руля"
    )

    fun gyroSteerSensTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Độ nhạy lái (Trái/Phải)",
        en = "Steering Sensitivity",
        es = "Sensibilidad de volante",
        pt = "Sensibilidade do volante",
        fr = "Sensibilité du volant",
        de = "Lenkempfindlichkeit",
        ja = "ステアリング感度",
        ko = "핸들 기울기 감도",
        zh = "方向盘倾斜灵敏度",
        id = "Sensitivitas Setir",
        th = "ความไวเอียงพวงมาลัย",
        ru = "Чувствительность руля"
    )

    fun gyroInvertXLabel(lang: PadLanguage, enabled: Boolean) = if (enabled) {
        tr(
            lang = lang,
            vi = "⇄ Đảo ngang: Bật",
            en = "⇄ Invert X: On",
            es = "⇄ Invertir X: Sí",
            pt = "⇄ Inverter X: Sim",
            fr = "⇄ Inverser X : Oui",
            de = "⇄ X umkehren: Ein",
            ja = "⇄ 左右反転: オン",
            ko = "⇄ 좌우 반전: 켜짐",
            zh = "⇄ 反转左右: 开",
            id = "⇄ Balik X: Aktif",
            th = "⇄ กลับซ้ายขวา: เปิด",
            ru = "⇄ Инверсия X: Вкл."
        )
    } else {
        tr(
            lang = lang,
            vi = "⇄ Đảo ngang: Tắt",
            en = "⇄ Invert X: Off",
            es = "⇄ Invertir X: No",
            pt = "⇄ Inverter X: Não",
            fr = "⇄ Inverser X : Non",
            de = "⇄ X umkehren: Aus",
            ja = "⇄ 左右反転: オフ",
            ko = "⇄ 좌우 반전: 꺼짐",
            zh = "⇄ 反转左右: 关",
            id = "⇄ Balik X: Mati",
            th = "⇄ กลับซ้ายขวา: ปิด",
            ru = "⇄ Инверсия X: Выкл."
        )
    }

    fun gyroRecenterLabel(lang: PadLanguage) = tr(
        lang = lang,
        vi = "🎯 Đặt lại tâm con quay (0°)",
        en = "🎯 Recenter Gyro (0°)",
        es = "🎯 Recentrar giroscopio (0°)",
        pt = "🎯 Recentralizar giroscópio (0°)",
        fr = "🎯 Recentrer le gyroscope (0°)",
        de = "🎯 Gyroskop zentrieren (0°)",
        ja = "🎯 ジャイロ中心をリセット (0°)",
        ko = "🎯 자이로 중심 재설정 (0°)",
        zh = "🎯 重置陀螺仪中心 (0°)",
        id = "🎯 Pusatkan Ulang Giroskop (0°)",
        th = "🎯 รีเซ็ตจุดศูนย์กลางไจโร (0°)",
        ru = "🎯 Центрировать гироскоп (0°)"
    )
}
