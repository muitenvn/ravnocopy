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
        vi = "CON QUAY HỒI CHUYỂN (THEO GAME / ĐUA XE)",
        en = "GYROSCOPE (GAME NATIVE / RACING)",
        es = "GIROSCOPIO (JUEGO NATIVO / CARRERAS)",
        pt = "GIROSCÓPIO (NATIVO DO JOGO / CORRIDA)",
        fr = "GYROSCOPE (JEU NATIF / COURSE)",
        de = "GYROSKOP (SPIEL-NATIV / RENNEN)",
        ja = "ジャイロ (ゲーム対応 / レース)",
        ko = "자이로스코프 (게임 지원 / 레이싱)",
        zh = "陀螺仪 (游戏原生 / 赛车)",
        id = "GIROSKOP (BAWAAN GAME / BALAPAN)",
        th = "ไจโรสโคป (ตามเกมรองรับ / แข่งรถ)",
        ru = "ГИРОСКОП (ПОДДЕРЖКА ИГРЫ / ГОНКИ)"
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
        vi = "Mặc định tắt — bật để ngắm Cần Phải, nghiêng lái xe hoặc theo game.",
        en = "Off by default — turn on for Right Stick aim, racing tilt, or game native.",
        es = "Apagado por defecto — actívalo para apuntar (R), carreras o nativo.",
        pt = "Desativado por padrão — ative para mirar (R), corrida ou nativo.",
        fr = "Désactivé par défaut — activez pour viser (R), course ou natif.",
        de = "Standardmäßig aus — aktivieren für Zielen (R), Rennen oder Nativ.",
        ja = "初期設定はオフ — 右スティック照準・レース・ゲーム対応に使用。",
        ko = "기본값 꺼짐 — 오른쪽 스틱 조준, 레이싱, 게임 지원에 사용.",
        zh = "默认关闭 — 开启后可用于右摇杆瞄准、赛车转向或游戏原生。",
        id = "Mati secara default — aktifkan untuk bidik (R), balapan, atau bawaan.",
        th = "ปิดเป็นค่าเริ่มต้น — เปิดเพื่อเล็งก้านขวา แข่งรถ หรือตามเกม",
        ru = "По умолчанию выкл. — прицел (R), гонки или нативный режим."
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

    fun gyroModeAimRightTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Ngắm bắn — Cần Phải (R)",
        en = "Aiming — Right Stick (R)",
        es = "Apuntar — Stick Derecho (R)",
        pt = "Mira — Analógico Direito (R)",
        fr = "Visée — Stick Droit (R)",
        de = "Zielen — Rechter Stick (R)",
        ja = "照準 — 右スティック (R)",
        ko = "조준 — 오른쪽 스틱 (R)",
        zh = "瞄准 — 右摇杆 (R)",
        id = "Bidik — Stik Kanan (R)",
        th = "เล็งยิง — ก้านขวา (R)",
        ru = "Прицел — Правый стик (R)"
    )

    fun gyroModeAimRightShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Ngắm (R)",
        en = "Aim (R)",
        es = "Apuntar (R)",
        pt = "Mira (R)",
        fr = "Visée (R)",
        de = "Zielen (R)",
        ja = "照準 (R)",
        ko = "조준 (R)",
        zh = "瞄准 (R)",
        id = "Bidik (R)",
        th = "เล็ง (R)",
        ru = "Прицел (R)"
    )

    fun gyroModeGameNativeTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Theo game hỗ trợ gốc",
        en = "Game Native Support",
        es = "Soporte nativo del juego",
        pt = "Suporte nativo do jogo",
        fr = "Support natif du jeu",
        de = "Native Spiel-Unterstützung",
        ja = "ゲーム標準対応",
        ko = "게임 기본 지원",
        zh = "游戏原生支持",
        id = "Dukungan Bawaan Game",
        th = "ตามเกมรองรับ",
        ru = "Поддержка игры"
    )

    fun gyroModeGameNativeShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Game gốc",
        en = "Native",
        es = "Nativo",
        pt = "Nativo",
        fr = "Natif",
        de = "Nativ",
        ja = "ゲーム",
        ko = "기본",
        zh = "原生",
        id = "Bawaan",
        th = "เกม",
        ru = "Игра"
    )

    fun gyroModeRacingTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đua xe — Nghiêng trái/phải (L)",
        en = "Racing — Left/Right Tilt (L)",
        es = "Carreras — Inclinar Izq./Der. (L)",
        pt = "Corrida — Inclinar Esq./Dir. (L)",
        fr = "Course — Inclinaison G/D (L)",
        de = "Rennen — Links/Rechts neigen (L)",
        ja = "レース — 左右傾き操作 (L)",
        ko = "레이싱 — 좌/우 기울기 조향 (L)",
        zh = "赛车 — 左右倾斜转向 (L)",
        id = "Balapan — Miring Kiri/Kanan (L)",
        th = "แข่งรถ — เอียงซ้าย/ขวา (L)",
        ru = "Гонки — Наклон влево/вправо (L)"
    )

    fun gyroModeRacingShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đua xe (L)",
        en = "Race (L)",
        es = "Carreras (L)",
        pt = "Corrida (L)",
        fr = "Course (L)",
        de = "Rennen (L)",
        ja = "レース (L)",
        ko = "레이싱 (L)",
        zh = "赛车 (L)",
        id = "Balapan (L)",
        th = "แข่งรถ (L)",
        ru = "Гонки (L)"
    )

    fun gyroHoldLtTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Chỉ bật khi giữ ngắm (LT)",
        en = "Only active while holding Aim (LT)",
        es = "Solo activo al mantener apuntar (LT)",
        pt = "Ativo apenas ao segurar mira (LT)",
        fr = "Actif uniquement en visant (LT)",
        de = "Nur beim Halten von Zielen (LT)",
        ja = "照準(LT)長押し中のみ有効",
        ko = "조준(LT) 누르는 동안만 활성",
        zh = "仅按住瞄准 (LT) 时启用",
        id = "Aktif hanya saat tahan Bidik (LT)",
        th = "เปิดเฉพาะตอนกดเล็ง (LT)",
        ru = "Только при удержании прицела (LT)"
    )

    fun gyroHoldLtSub(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Nhả LT là khóa tâm ngay, tránh chóng mặt khi di chuyển.",
        en = "Release LT to lock aim immediately and avoid motion sickness.",
        es = "Suelta LT para bloquear la mira al instante.",
        pt = "Solte LT para travar a mira imediatamente.",
        fr = "Relâchez LT pour verrouiller la visée immédiatement.",
        de = "LT loslassen, um das Zielen sofort zu sperren.",
        ja = "LTを離すと即座に照準を固定し酔いを防ぎます。",
        ko = "LT를 떼면 조준이 즉시 고정되어 어지럼증을 줄입니다.",
        zh = "松开 LT 立即锁定准星，移动时不头晕。",
        id = "Lepas LT untuk kunci bidikan seketika.",
        th = "ปล่อย LT เพื่อล็อกเป้าทันที ลดอาการเวียนหัว",
        ru = "Отпустите LT для мгновенной фиксации прицела."
    )

    fun gyroHoldLtChip(lang: PadLanguage, enabled: Boolean) = if (enabled) {
        tr(
            lang = lang,
            vi = "Giữ LT ngắm: Bật",
            en = "Hold LT: On",
            es = "Mantener LT: Sí",
            pt = "Segurar LT: Sim",
            fr = "Maintenir LT : Oui",
            de = "LT halten: Ein",
            ja = "LT長押し: オン",
            ko = "LT 유지: 켜짐",
            zh = "按住 LT: 开",
            id = "Tahan LT: Aktif",
            th = "กดค้าง LT: เปิด",
            ru = "Удерж. LT: Вкл."
        )
    } else {
        tr(
            lang = lang,
            vi = "Giữ LT ngắm: Tắt",
            en = "Hold LT: Off",
            es = "Mantener LT: No",
            pt = "Segurar LT: Não",
            fr = "Maintenir LT : Non",
            de = "LT halten: Aus",
            ja = "LT長押し: オフ",
            ko = "LT 유지: 꺼짐",
            zh = "按住 LT: 关",
            id = "Tahan LT: Mati",
            th = "กดค้าง LT: ปิด",
            ru = "Удерж. LT: Выкл."
        )
    }

    fun gyroSensXShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Độ nhạy ngang (X)",
        en = "Horizontal sens (X)",
        es = "Sens. horizontal (X)",
        pt = "Sens. horizontal (X)",
        fr = "Sens. horizontale (X)",
        de = "Empfindl. X",
        ja = "横感度 (X)",
        ko = "가로 감도 (X)",
        zh = "水平灵敏度 (X)",
        id = "Sens. Horizontal (X)",
        th = "ความไวแนวนอน (X)",
        ru = "Чувств. гориз. (X)"
    )

    fun gyroSensYShort(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Độ nhạy dọc (Y)",
        en = "Vertical sens (Y)",
        es = "Sens. vertical (Y)",
        pt = "Sens. vertical (Y)",
        fr = "Sens. verticale (Y)",
        de = "Empfindl. Y",
        ja = "縦感度 (Y)",
        ko = "세로 감도 (Y)",
        zh = "垂直灵敏度 (Y)",
        id = "Sens. Vertikal (Y)",
        th = "ความไวแนวตั้ง (Y)",
        ru = "Чувств. верт. (Y)"
    )

    fun gyroGameNativePadNotice(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Không giả lập cần tay cầm ở chế độ này. Chọn Ngắm (R) hoặc Đua xe (L) để dùng cảm biến nghiêng.",
        en = "No stick emulation in this mode. Switch to Aim (R) or Race (L) to use motion sensors.",
        es = "Sin emulación de stick en este modo. Cambia a Apuntar (R) o Carreras (L).",
        pt = "Sem emulação de analógico neste modo. Mude para Mira (R) ou Corrida (L).",
        fr = "Aucune émulation de stick ici. Passez en Visée (R) ou Course (L).",
        de = "Keine Stick-Emulation in diesem Modus. Wechsle zu Zielen (R) oder Rennen (L).",
        ja = "このモードではスティック擬似入力を行いません。照準(R)またはレース(L)へ切替えてください。",
        ko = "이 모드에서는 스틱 가상 입력이 없습니다. 조준(R) 또는 레이싱(L)으로 전환하세요.",
        zh = "此模式不模拟摇杆。请切换到瞄准 (R) 或赛车 (L) 使用体感。",
        id = "Tidak ada emulasi stik di mode ini. Beralih ke Bidik (R) atau Balapan (L).",
        th = "โหมดนี้ไม่จำลองก้านอนาล็อก สลับไปที่ เล็ง (R) หรือ แข่งรถ (L) เพื่อใช้เซ็นเซอร์",
        ru = "В этом режиме эмуляция стика отключена. Выберите Прицел (R) или Гонки (L)."
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

    fun gyroInvertYLabel(lang: PadLanguage, enabled: Boolean) = if (enabled) {
        tr(
            lang = lang,
            vi = "⇅ Đảo dọc: Bật",
            en = "⇅ Invert Y: On",
            es = "⇅ Invertir Y: Sí",
            pt = "⇅ Inverter Y: Sim",
            fr = "⇅ Inverser Y : Oui",
            de = "⇅ Y umkehren: Ein",
            ja = "⇅ 上下反転: オン",
            ko = "⇅ 상하 반전: 켜짐",
            zh = "⇅ 反转上下: 开",
            id = "⇅ Balik Y: Aktif",
            th = "⇅ กลับบนล่าง: เปิด",
            ru = "⇅ Инверсия Y: Вкл."
        )
    } else {
        tr(
            lang = lang,
            vi = "⇅ Đảo dọc: Tắt",
            en = "⇅ Invert Y: Off",
            es = "⇅ Invertir Y: No",
            pt = "⇅ Inverter Y: Não",
            fr = "⇅ Inverser Y : Non",
            de = "⇅ Y umkehren: Aus",
            ja = "⇅ 上下反転: オフ",
            ko = "⇅ 상하 반전: 꺼짐",
            zh = "⇅ 反转上下: 关",
            id = "⇅ Balik Y: Mati",
            th = "⇅ กลับบนล่าง: ปิด",
            ru = "⇅ Инверсия Y: Выкл."
        )
    }

    fun gyroRecenterLabel(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đặt lại tâm con quay (0°)",
        en = "Recenter Gyro (0°)",
        es = "Recentrar giroscopio (0°)",
        pt = "Recentralizar giroscópio (0°)",
        fr = "Recentrer le gyroscope (0°)",
        de = "Gyroskop zentrieren (0°)",
        ja = "ジャイロ中心をリセット (0°)",
        ko = "자이로 중심 재설정 (0°)",
        zh = "重置陀螺仪中心 (0°)",
        id = "Pusatkan Ulang Giroskop (0°)",
        th = "รีเซ็ตจุดศูนย์กลางไจโร (0°)",
        ru = "Центрировать гироскоп (0°)"
    )

    fun manualTabIp(lang: PadLanguage) = tr(
        lang = lang,
        vi = "IP (LAN)",
        en = "IP (LAN)",
        es = "IP (LAN)",
        pt = "IP (LAN)",
        fr = "IP (LAN)",
        de = "IP (LAN)",
        ja = "IP (LAN)",
        ko = "IP (LAN)",
        zh = "IP (局域网)",
        id = "IP (LAN)",
        th = "IP (LAN)",
        ru = "IP (LAN)"
    )

    fun manualTabAdvanced(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Nâng cao (Mã)",
        en = "Advanced (Code)",
        es = "Avanzado (Código)",
        pt = "Avançado (Código)",
        fr = "Avancé (Code)",
        de = "Erweitert (Code)",
        ja = "詳細 (コード)",
        ko = "고급 (코드)",
        zh = "高级 (连接码)",
        id = "Lanjutan (Kode)",
        th = "ขั้นสูง (รหัส)",
        ru = "Доп. (Код)"
    )

    fun remoteCodePlaceholder(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Mã 10 số (00000-00000)",
        en = "10-digit code (00000-00000)",
        es = "Código de 10 dígitos",
        pt = "Código de 10 dígitos",
        fr = "Code à 10 chiffres",
        de = "10-stelliger Zahlencode",
        ja = "10桁の数字コード",
        ko = "10자리 숫자 코드",
        zh = "10位数字代码 (00000-00000)",
        id = "Kode 10 angka (00000-00000)",
        th = "รหัสตัวเลข 10 หลัก",
        ru = "10-значный код (00000-00000)"
    )

    fun remoteConnectBtn(lang: PadLanguage) = tr(
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
        ru = "Подключить"
    )

    fun remoteStatusConnecting(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang nối máy chủ mã hóa…",
        en = "Connecting to encrypted relay…",
        es = "Conectando al relé cifrado…",
        pt = "Conectando ao relé criptografado…",
        fr = "Connexion au relais chiffré…",
        de = "Verbindung zum verschlüsselten Relay…",
        ja = "暗号化リレーに接続中…",
        ko = "암호화 릴레이에 연결 중…",
        zh = "正在连接加密中继…",
        id = "Menghubungkan ke relai terenkripsi…",
        th = "กำลังเชื่อมต่อรีเลย์เข้ารหัส…",
        ru = "Подключение к зашифрованному реле…"
    )

    fun remoteStatusHandshake(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang xác thực mã với TV…",
        en = "Verifying code with TV…",
        es = "Verificando código con la TV…",
        pt = "Verificando código com a TV…",
        fr = "Vérification du code avec la TV…",
        de = "Code wird mit dem TV geprüft…",
        ja = "TVとコードを確認中…",
        ko = "TV와 코드를 확인하는 중…",
        zh = "正在与电视验证连接码…",
        id = "Memverifikasi kode dengan TV…",
        th = "กำลังยืนยันรหัสกับทีวี…",
        ru = "Проверка кода с ТВ…"
    )

    fun remoteStatusTryingLan(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang thử nối thẳng LAN…",
        en = "Trying direct LAN upgrade…",
        es = "Probando conexión directa LAN…",
        pt = "Tentando conexão direta LAN…",
        fr = "Tentative de liaison directe LAN…",
        de = "Direkte LAN-Verbindung wird getestet…",
        ja = "直接LAN接続を試行中…",
        ko = "직접 LAN 연결 시도 중…",
        zh = "正在尝试直连局域网…",
        id = "Mencoba sambungan LAN langsung…",
        th = "กำลังลองเชื่อมต่อ LAN โดยตรง…",
        ru = "Попытка прямого LAN-соединения…"
    )

    fun remoteStatusInvalidCode(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Nhập đủ 10 chữ số (0–9) đang hiển thị trên TV.",
        en = "Enter all 10 digits (0–9) shown on your TV.",
        es = "Ingresa los 10 dígitos (0–9) mostrados en la TV.",
        pt = "Digite os 10 números (0–9) exibidos na TV.",
        fr = "Saisissez les 10 chiffres (0–9) affichés sur la TV.",
        de = "Gib alle 10 Ziffern (0–9) vom TV ein.",
        ja = "TVに表示された10桁の数字（0〜9）を入力してください。",
        ko = "TV에 표시된 10자리 숫자(0–9)를 모두 입력하세요.",
        zh = "请输入电视上显示的全部10位数字（0–9）。",
        id = "Masukkan seluruh 10 angka (0–9) yang tampil di TV.",
        th = "กรอกตัวเลขให้ครบ 10 หลัก (0–9) ตามที่แสดงบนทีวี",
        ru = "Введите все 10 цифр (0–9), показанные на ТВ."
    )

    fun remoteStatusBrokerError(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Không nối được máy chủ trung gian. Kiểm tra mạng Internet.",
        en = "Could not reach relay server. Check your internet connection.",
        es = "No se pudo conectar al servidor relé. Revisa tu internet.",
        pt = "Não foi possível acessar o servidor relé. Verifique sua internet.",
        fr = "Impossible de joindre le serveur relais. Vérifiez votre connexion.",
        de = "Relay-Server nicht erreichbar. Internetverbindung prüfen.",
        ja = "中継サーバーに接続できません。ネット接続を確認してください。",
        ko = "릴레이 서버에 연결할 수 없습니다. 인터넷 연결을 확인하세요.",
        zh = "无法连接中继服务器，请检查网络连接。",
        id = "Tidak dapat menjangkau server relai. Periksa koneksi internet.",
        th = "เชื่อมต่อเซิร์ฟเวอร์รีเลย์ไม่ได้ โปรดตรวจสอบอินเทอร์เน็ต",
        ru = "Не удалось подключиться к реле-серверу. Проверьте интернет."
    )

    fun remoteStatusTimeout(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Không thấy TV dùng mã này. Kiểm tra lại mã đang mở trên TV.",
        en = "No TV responded to this code. Check the active code on your TV.",
        es = "Ninguna TV respondió a este código. Revisa el código activo en la TV.",
        pt = "Nenhuma TV respondeu a este código. Confira o código ativo na TV.",
        fr = "Aucune TV n'a répondu à ce code. Vérifiez le code affiché sur la TV.",
        de = "Kein TV hat auf diesen Code geantwortet. Prüfe den Code am TV.",
        ja = "このコードに一致するTVが見つかりません。TVのコードを確認してください。",
        ko = "이 코드로 응답하는 TV가 없습니다. TV에 표시된 코드를 확인하세요.",
        zh = "未找到使用此代码的电视，请核对电视上显示的代码。",
        id = "Tidak ada TV yang merespons kode ini. Periksa kode aktif di TV.",
        th = "ไม่พบทีวีที่ใช้รหัสนี้ โปรดตรวจสอบรหัสที่เปิดอยู่บนทีวี",
        ru = "ТВ с этим кодом не отвечает. Проверьте активный код на ТВ."
    )

    fun remoteStatusLockedOut(lang: PadLanguage, timeStr: String) = tr(
        lang = lang,
        vi = "TV tạm khóa do nhập sai 3 lần · Chờ $timeStr",
        en = "TV temporarily locked after 3 failed attempts · Wait $timeStr",
        es = "TV bloqueada tras 3 intentos fallidos · Espera $timeStr",
        pt = "TV bloqueada após 3 tentativas falhas · Aguarde $timeStr",
        fr = "TV verrouillée après 3 échecs · Patientez $timeStr",
        de = "TV nach 3 Fehlversuchen gesperrt · Warte $timeStr",
        ja = "3回失敗のためTVが一時ロック中 · 残り $timeStr",
        ko = "3회 실패로 TV가 일시 잠겼습니다 · 대기 $timeStr",
        zh = "连续失败3次，电视已暂时锁定 · 请等待 $timeStr",
        id = "TV dikunci sementara setelah 3 kali gagal · Tunggu $timeStr",
        th = "ทีวีล็อกชั่วคราวหลังใส่ผิด 3 ครั้ง · รอ $timeStr",
        ru = "ТВ временно заблокирован после 3 ошибок · Ждите $timeStr"
    )

    fun remoteTransportBadge(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Từ xa",
        en = "Remote",
        es = "Remoto",
        pt = "Remoto",
        fr = "Distant",
        de = "Fern",
        ja = "リモート",
        ko = "원격",
        zh = "远程",
        id = "Jarak jauh",
        th = "ระยะไกล",
        ru = "Удалённо"
    )

    fun remoteDisconnectedSubtitle(lang: PadLanguage, tvName: String) = tr(
        lang = lang,
        vi = "Mất kết nối từ xa với $tvName. Mọi nút bấm đã tự nhả về trạng thái nghỉ.",
        en = "Remote connection to $tvName was lost. All buttons were safely released.",
        es = "Se perdió la conexión remota con $tvName. Todos los botones se soltaron.",
        pt = "Conexão remota com $tvName perdida. Todos os botões foram soltos.",
        fr = "Connexion distante avec $tvName perdue. Tous les boutons ont été relâchés.",
        de = "Fernverbindung zu $tvName getrennt. Alle Tasten wurden sicher gelöst.",
        ja = "$tvName とのリモート接続が切断されました。すべてのボタンを安全に解放しました。",
        ko = "$tvName 와의 원격 연결이 끊어졌습니다. 모든 버튼이 안전하게 해제되었습니다.",
        zh = "与 $tvName 的远程连接已断开，所有按键已自动松开。",
        id = "Koneksi jarak jauh ke $tvName terputus. Semua tombol dilepas otomatis.",
        th = "การเชื่อมต่อระยะไกลกับ $tvName ขาดหาย ปล่อยปุ่มทั้งหมดโดยอัตโนมัติแล้ว",
        ru = "Удалённое соединение с $tvName прервано. Все кнопки безопасно отпущены."
    )

    fun updateAvailableTitle(lang: PadLanguage, latestVer: String) = tr(
        lang = lang,
        vi = "Có bản cập nhật Ravno Pad v$latestVer",
        en = "Ravno Pad v$latestVer Available",
        es = "Ravno Pad v$latestVer disponible",
        pt = "Ravno Pad v$latestVer disponível",
        fr = "Ravno Pad v$latestVer disponible",
        de = "Ravno Pad v$latestVer verfügbar",
        ja = "Ravno Pad v$latestVer が利用可能",
        ko = "Ravno Pad v$latestVer 업데이트 가능",
        zh = "Ravno Pad v$latestVer 新版本可用",
        id = "Ravno Pad v$latestVer Tersedia",
        th = "มีอัปเดต Ravno Pad v$latestVer",
        ru = "Доступно обновление Ravno Pad v$latestVer"
    )

    fun updateVersionDiff(lang: PadLanguage, currentVer: String, latestVer: String) = tr(
        lang = lang,
        vi = "Đang dùng v$currentVer → Bản mới v$latestVer",
        en = "Current v$currentVer → New v$latestVer",
        es = "Actual v$currentVer → Nueva v$latestVer",
        pt = "Atual v$currentVer → Nova v$latestVer",
        fr = "Actuelle v$currentVer → Nouvelle v$latestVer",
        de = "Aktuell v$currentVer → Neu v$latestVer",
        ja = "現在 v$currentVer → 最新 v$latestVer",
        ko = "현재 v$currentVer → 최신 v$latestVer",
        zh = "当前 v$currentVer → 最新 v$latestVer",
        id = "Saat ini v$currentVer → Baru v$latestVer",
        th = "ปัจจุบัน v$currentVer → ใหม่ v$latestVer",
        ru = "Текущая v$currentVer → Новая v$latestVer"
    )

    fun updateDownloadingStatus(lang: PadLanguage, mirrorName: String, percentText: String) = tr(
        lang = lang,
        vi = "Đang tự động tải ($mirrorName)… $percentText",
        en = "Auto-downloading ($mirrorName)… $percentText",
        es = "Descargando automáticamente ($mirrorName)… $percentText",
        pt = "Baixando automaticamente ($mirrorName)… $percentText",
        fr = "Téléchargement auto ($mirrorName)… $percentText",
        de = "Automatischer Download ($mirrorName)… $percentText",
        ja = "自動ダウンロード中 ($mirrorName)… $percentText",
        ko = "자동 다운로드 중 ($mirrorName)… $percentText",
        zh = "正在自动下载 ($mirrorName)… $percentText",
        id = "Mengunduh otomatis ($mirrorName)… $percentText",
        th = "กำลังดาวน์โหลดอัตโนมัติ ($mirrorName)… $percentText",
        ru = "Автозагрузка ($mirrorName)… $percentText"
    )

    fun updateVerifyingShaStatus(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đang xác thực mã SHA-256…",
        en = "Verifying SHA-256 checksum…",
        es = "Verificando suma SHA-256…",
        pt = "Verificando SHA-256…",
        fr = "Vérification SHA-256…",
        de = "SHA-256 wird geprüft…",
        ja = "SHA-256 を検証中…",
        ko = "SHA-256 무결성 확인 중…",
        zh = "正在校验 SHA-256…",
        id = "Memverifikasi SHA-256…",
        th = "กำลังตรวจสอบ SHA-256…",
        ru = "Проверка SHA-256…"
    )

    fun updateVerifiedReadyStatus(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đã tải xong & xác thực SHA-256 hợp lệ. Sẵn sàng cài đặt!",
        en = "Downloaded & SHA-256 verified. Ready to install!",
        es = "Descargado y SHA-256 verificado. ¡Listo para instalar!",
        pt = "Baixado e SHA-256 verificado. Pronto para instalar!",
        fr = "Téléchargé et vérifié (SHA-256). Prêt à installer !",
        de = "Geladen & SHA-256 geprüft. Bereit zur Installation!",
        ja = "ダウンロード・SHA-256 検証完了。インストール準備完了！",
        ko = "다운로드 및 SHA-256 검증 완료. 설치 준비됨!",
        zh = "下载完成且 SHA-256 校验通过，可立即安装！",
        id = "Unduhan & SHA-256 terverifikasi. Siap dipasang!",
        th = "ดาวน์โหลดและตรวจ SHA-256 ผ่านแล้ว พร้อมติดตั้ง!",
        ru = "Загружено и проверено по SHA-256. Готово к установке!"
    )

    fun updateDownloadErrorStatus(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Không tải được qua các máy chủ. Hãy thử lại hoặc mở GitHub.",
        en = "Could not download via mirrors. Try again or open GitHub.",
        es = "No se pudo descargar. Reintenta o abre GitHub.",
        pt = "Falha ao baixar. Tente novamente ou abra o GitHub.",
        fr = "Échec du téléchargement. Réessayez ou ouvrez GitHub.",
        de = "Download fehlgeschlagen. Erneut versuchen oder GitHub öffnen.",
        ja = "ダウンロードに失敗しました。再試行またはGitHubを開いてください。",
        ko = "다운로드에 실패했습니다. 다시 시도하거나 GitHub를 여세요.",
        zh = "下载失败，请重试或打开 GitHub 页面。",
        id = "Gagal mengunduh. Coba lagi atau buka GitHub.",
        th = "ดาวน์โหลดไม่สำเร็จ ลองอีกครั้งหรือเปิด GitHub",
        ru = "Не удалось скачать. Повторите попытку или откройте GitHub."
    )

    fun updateInstallNowBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Cài đặt ngay",
        en = "Install Now",
        es = "Instalar ahora",
        pt = "Instalar agora",
        fr = "Installer",
        de = "Jetzt installieren",
        ja = "今すぐインストール",
        ko = "지금 설치",
        zh = "立即安装",
        id = "Pasang Sekarang",
        th = "ติดตั้งเลย",
        ru = "Установить"
    )

    fun updateDownloadBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Tải & Cài đặt",
        en = "Download & Install",
        es = "Descargar e instalar",
        pt = "Baixar e instalar",
        fr = "Télécharger",
        de = "Laden & installieren",
        ja = "ダウンロード",
        ko = "다운로드 및 설치",
        zh = "下载并安装",
        id = "Unduh & Pasang",
        th = "ดาวน์โหลดและติดตั้ง",
        ru = "Скачать и установить"
    )

    fun updateSkipMirrorBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đổi máy chủ",
        en = "Next Mirror",
        es = "Otro servidor",
        pt = "Outro servidor",
        fr = "Autre miroir",
        de = "Nächster Server",
        ja = "別サーバーへ",
        ko = "다른 서버",
        zh = "切换节点",
        id = "Ganti Server",
        th = "เปลี่ยนเซิร์ฟเวอร์",
        ru = "Другой сервер"
    )

    fun updateOpenGithubBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Mở GitHub",
        en = "Open GitHub",
        es = "Abrir GitHub",
        pt = "Abrir GitHub",
        fr = "Ouvrir GitHub",
        de = "GitHub öffnen",
        ja = "GitHub を開く",
        ko = "GitHub 열기",
        zh = "打开 GitHub",
        id = "Buka GitHub",
        th = "เปิด GitHub",
        ru = "Открыть GitHub"
    )

    fun updateLaterBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Để sau",
        en = "Later",
        es = "Más tarde",
        pt = "Depois",
        fr = "Plus tard",
        de = "Später",
        ja = "あとで",
        ko = "나중에",
        zh = "稍后",
        id = "Nanti",
        th = "ภายหลัง",
        ru = "Позже"
    )

    fun checkUpdatePillLabel(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Cập nhật",
        en = "Updates",
        es = "Actualizar",
        pt = "Atualizar",
        fr = "Mises à jour",
        de = "Updates",
        ja = "更新確認",
        ko = "업데이트",
        zh = "检查更新",
        id = "Pembaruan",
        th = "อัปเดต",
        ru = "Обновления"
    )

    fun updateUpToDateBanner(lang: PadLanguage, ver: String) = tr(
        lang = lang,
        vi = "Đã là bản mới nhất (v$ver)",
        en = "Up to date (v$ver)",
        es = "Actualizado (v$ver)",
        pt = "Atualizado (v$ver)",
        fr = "À jour (v$ver)",
        de = "Aktuell (v$ver)",
        ja = "最新バージョンです (v$ver)",
        ko = "최신 버전입니다 (v$ver)",
        zh = "已是最新版本 (v$ver)",
        id = "Sudah terbaru (v$ver)",
        th = "เป็นเวอร์ชันล่าสุดแล้ว (v$ver)",
        ru = "Установлена последняя версия (v$ver)"
    )

    fun termsButtonLabel(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Điều khoản",
        en = "Terms",
        es = "Términos",
        pt = "Termos",
        fr = "Conditions",
        de = "Bedingungen",
        ja = "利用規約",
        ko = "이용약관",
        zh = "使用条款",
        id = "Ketentuan",
        th = "ข้อกำหนด",
        ru = "Условия"
    )

    fun termsDialogTitle(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Điều Khoản Sử Dụng & Quyền Riêng Tư",
        en = "Terms of Use & Privacy Policy",
        es = "Términos de Uso y Privacidad",
        pt = "Termos de Uso e Privacidade",
        fr = "Conditions d'utilisation et Confidentialité",
        de = "Nutzungsbedingungen & Datenschutz",
        ja = "利用規約とプライバシーポリシー",
        ko = "이용약관 및 개인정보 처리방침",
        zh = "使用条款与隐私政策",
        id = "Ketentuan Penggunaan & Privasi",
        th = "ข้อกำหนดการใช้งานและความเป็นส่วนตัว",
        ru = "Условия использования и конфиденциальность"
    )

    fun termsDialogBody(lang: PadLanguage) = tr(
        lang = lang,
        vi = "• Ravno Pad là ứng dụng độc lập, không liên kết Microsoft/Xbox.\n" +
            "• Mặc định chỉ kết nối trực tiếp trong mạng Wi-Fi nội bộ (LAN) qua PIN 4 số + HMAC.\n" +
            "• Chế độ nâng cao khác Wi-Fi (tùy chọn) dùng mã 10 chữ số với mã hóa đầu-cuối TLS + PBKDF2 + ECDH P-256 + AES-256-GCM; không yêu cầu đăng nhập Google.\n" +
            "• Tự động kiểm tra bản mới từ GitHub (muitenvn/Ravno-App) và xác thực SHA-256 trước khi cài.\n" +
            "• Không quảng cáo, không theo dõi, không thu thập dữ liệu cá nhân.",
        en = "• Ravno Pad is an independent app, not affiliated with Microsoft or Xbox.\n" +
            "• By default it connects strictly over local Wi-Fi (LAN) with 4-digit PIN + HMAC.\n" +
            "• Optional off-LAN mode uses a 10-digit numeric code with end-to-end encryption (TLS + PBKDF2 + ECDH P-256 + AES-256-GCM); no Google Sign-In required.\n" +
            "• Checks official GitHub Releases (muitenvn/Ravno-App) and verifies SHA-256 before installing updates.\n" +
            "• Zero ads, zero tracking, zero personal data collection.",
        es = "• Ravno Pad es una app independiente, no afiliada a Microsoft ni Xbox.\n" +
            "• Por defecto solo conecta por Wi-Fi local (LAN) con PIN de 4 dígitos + HMAC.\n" +
            "• El modo remoto opcional usa código numérico de 10 dígitos con cifrado de extremo a extremo (TLS + PBKDF2 + ECDH + AES-256-GCM), sin cuenta Google.\n" +
            "• Comprueba actualizaciones en GitHub y verifica SHA-256 antes de instalar.\n" +
            "• Sin anuncios, sin rastreo y sin recopilación de datos personales.",
        pt = "• Ravno Pad é um app independente, sem vínculo com Microsoft ou Xbox.\n" +
            "• Por padrão conecta apenas no Wi-Fi local (LAN) com PIN de 4 dígitos + HMAC.\n" +
            "• O modo remoto opcional usa código numérico de 10 dígitos com criptografia ponta a ponta (TLS + PBKDF2 + ECDH + AES-256-GCM), sem login Google.\n" +
            "• Verifica atualizações no GitHub e valida SHA-256 antes de instalar.\n" +
            "• Sem anúncios, sem rastreamento e sem coleta de dados pessoais.",
        fr = "• Ravno Pad est une app indépendante, non affiliée à Microsoft ou Xbox.\n" +
            "• Par défaut, connexion uniquement en Wi-Fi local (LAN) avec PIN 4 chiffres + HMAC.\n" +
            "• Le mode distant optionnel utilise un code à 10 chiffres chiffré de bout en bout (TLS + PBKDF2 + ECDH + AES-256-GCM), sans compte Google.\n" +
            "• Vérifie les mises à jour GitHub et contrôle le SHA-256 avant installation.\n" +
            "• Sans publicité, sans suivi, sans collecte de données personnelles.",
        de = "• Ravno Pad ist eine unabhängige App, nicht mit Microsoft/Xbox verbunden.\n" +
            "• Standardmäßig nur lokales WLAN (LAN) mit 4-stelligem PIN + HMAC.\n" +
            "• Optionaler Fernmodus nutzt 10-stelligen Zahlencode mit Ende-zu-Ende-Verschlüsselung (TLS + PBKDF2 + ECDH + AES-256-GCM), ohne Google-Konto.\n" +
            "• Prüft GitHub-Updates und verifiziert SHA-256 vor der Installation.\n" +
            "• Keine Werbung, kein Tracking, keine Datensammlung.",
        ja = "• Ravno Padは独立したアプリであり、MicrosoftやXboxとは無関係です。\n" +
            "• 既定では同一Wi-Fi（LAN）のみで4桁PIN＋HMAC接続します。\n" +
            "• オプションの別ネットワーク接続は10桁の数字コード＋エンドツーエンド暗号化（TLS＋PBKDF2＋ECDH＋AES-256-GCM）を使用し、Googleログイン不要です。\n" +
            "• GitHub公式リリースから更新を確認し、SHA-256検証後にインストールします。\n" +
            "• 広告・追跡・個人情報収集は一切ありません。",
        ko = "• Ravno Pad는 Microsoft/Xbox와 무관한 독립 앱입니다.\n" +
            "• 기본값은 동일 Wi-Fi(LAN)에서 4자리 PIN + HMAC으로만 연결됩니다.\n" +
            "• 선택형 원격 모드는 10자리 숫자 코드와 종단간 암호화(TLS + PBKDF2 + ECDH + AES-256-GCM)를 사용하며 Google 로그인이 필요 없습니다.\n" +
            "• GitHub 공식 릴리스에서 업데이트를 확인하고 SHA-256 검증 후 설치합니다.\n" +
            "• 광고, 추적, 개인정보 수집이 전혀 없습니다.",
        zh = "• Ravno Pad 为独立应用，与 Microsoft/Xbox 无关联。\n" +
            "• 默认仅在局域网 Wi-Fi (LAN) 下通过 4 位 PIN + HMAC 连接。\n" +
            "• 可选跨网模式使用 10 位数字代码与端到端加密（TLS + PBKDF2 + ECDH + AES-256-GCM），无需登录 Google。\n" +
            "• 从官方 GitHub Releases 检查更新并在安装前校验 SHA-256。\n" +
            "• 无广告、无追踪、不收集个人数据。",
        id = "• Ravno Pad adalah aplikasi independen, tidak berafiliasi dengan Microsoft/Xbox.\n" +
            "• Secara default hanya terhubung di Wi-Fi lokal (LAN) dengan PIN 4 digit + HMAC.\n" +
            "• Mode jarak jauh opsional memakai kode 10 angka terenkripsi ujung-ke-ujung (TLS + PBKDF2 + ECDH + AES-256-GCM) tanpa login Google.\n" +
            "• Memeriksa pembaruan GitHub resmi dan memverifikasi SHA-256 sebelum memasang.\n" +
            "• Tanpa iklan, tanpa pelacakan, tanpa pengumpulan data pribadi.",
        th = "• Ravno Pad เป็นแอปอิสระ ไม่เกี่ยวข้องกับ Microsoft หรือ Xbox\n" +
            "• ค่าเริ่มต้นเชื่อมต่อเฉพาะ Wi-Fi วงเดียวกัน (LAN) ด้วย PIN 4 หลัก + HMAC\n" +
            "• โหมดข้ามเครือข่าย (เลือกเปิดได้) ใช้รหัสตัวเลข 10 หลักพร้อมเข้ารหัสต้นทางถึงปลายทาง (TLS + PBKDF2 + ECDH + AES-256-GCM) ไม่ต้องล็อกอิน Google\n" +
            "• ตรวจสอบอัปเดตจาก GitHub ทางการและตรวจ SHA-256 ก่อนติดตั้ง\n" +
            "• ไม่มีโฆษณา ไม่มีการติดตาม และไม่เก็บข้อมูลส่วนตัว",
        ru = "• Ravno Pad — независимое приложение, не связанное с Microsoft и Xbox.\n" +
            "• По умолчанию работает только в локальной сети Wi-Fi (LAN) по 4-значному PIN + HMAC.\n" +
            "• Опциональный удалённый режим использует 10-значный цифровой код со сквозным шифрованием (TLS + PBKDF2 + ECDH + AES-256-GCM), без аккаунта Google.\n" +
            "• Проверяет обновления на GitHub и сверяет SHA-256 перед установкой.\n" +
            "• Без рекламы, без слежки и без сбора личных данных."
    )

    fun termsViewFullWebBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Xem đầy đủ trên web",
        en = "Read Full Terms",
        es = "Ver términos completos",
        pt = "Ver termos completos",
        fr = "Voir le texte complet",
        de = "Vollständige Bedingungen",
        ja = "全文をウェブで見る",
        ko = "전체 약관 보기",
        zh = "查看完整条款",
        id = "Baca Selengkapnya",
        th = "อ่านฉบับเต็ม",
        ru = "Полный текст"
    )

    fun termsAgreeBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Tôi đồng ý",
        en = "I Agree",
        es = "Acepto",
        pt = "Concordo",
        fr = "J'accepte",
        de = "Zustimmen",
        ja = "同意する",
        ko = "동의함",
        zh = "我同意",
        id = "Saya Setuju",
        th = "ฉันยอมรับ",
        ru = "Принять"
    )

    fun termsDeclineBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Từ chối (Thoát)",
        en = "Decline (Exit)",
        es = "Rechazar (Salir)",
        pt = "Recusar (Sair)",
        fr = "Refuser (Quitter)",
        de = "Ablehnen (Beenden)",
        ja = "同意しない（終了）",
        ko = "거부 (종료)",
        zh = "拒绝并退出",
        id = "Tolak (Keluar)",
        th = "ปฏิเสธ (ออก)",
        ru = "Отклонить (Выход)"
    )

    fun closeBtn(lang: PadLanguage) = tr(
        lang = lang,
        vi = "Đóng",
        en = "Close",
        es = "Cerrar",
        pt = "Fechar",
        fr = "Fermer",
        de = "Schließen",
        ja = "閉じる",
        ko = "닫기",
        zh = "关闭",
        id = "Tutup",
        th = "ปิด",
        ru = "Закрыть"
    )
}
