// SleepGuardShow.swift — お風呂と就寝を促す文面を画面いっぱいに表示する
//
// sleepguard.sh からログイン中のユーザーの権限で起動される。install.sh が swiftc でコンパイルする。
//   --kind bath|sleep   入浴の時間帯(「入浴済み」ボタンあり)か、就寝の時間帯か
//   --band H:MM         文面ファイルのどの見出しの文を出すか
//   --day YYYYMMDD      夜の日付。その夜に出した文の記録に使う(省略時は今日)
//   --messages PATH     文面ファイル(省略時は ~/.config/sleepguard/messages.md)
//   --text 文           ファイルを読まずにこの文を出す
//   --no-record         出した文を記録しない(プレビュー用)
//   --away SECONDS      入浴の画面で、これだけ離席したら入浴済みとみなして閉じる(省略時は 900)
//   --quick             15秒・30秒・2分の待ち時間を 2秒・4秒・20秒に、--away を 10秒にする(プレビュー用)
// 終了コード: 0 閉じた / 自動で閉じた、2 「入浴済み」を押した、3 離席したので入浴済みとみなした、1 引数の誤り
//
// 表示の流れ:
//   0秒   1秒かけて現れる。クリックもキーも全部受け止め、下のアプリには届かない。Cmd+Q も効かない
//   15秒  背景が半透明になり、クリックもキーも下のアプリに素通しする。まだ閉じられない
//   30秒  画面下にバーが出る。就寝なら「閉じる」、入浴なら「入浴済み」だけ。閉じるのはこのボタンだけ
//   2分   就寝の画面は自動で閉じる。入浴の画面は閉じるまで残り、離席が --away 秒に達したら入浴済みとして閉じる
// 止める裏技はプロセスを kill するだけ(解除対策はしない方針)。

import AppKit

setlinebuf(stdout)   // ログ(launchd の出力先)にすぐ書き出す

// MARK: - 引数

struct Options {
    var kind = "sleep"
    var band = ""
    var day = ""
    var messages = ""
    var text: String? = nil
    var record = true
    var quick = false
    var away = 900.0
}

func usage() -> Never {
    let s = "使い方: sleepguard-show --kind bath|sleep --band H:MM [--day YYYYMMDD] [--messages PATH] [--text 文] [--no-record] [--away SECONDS] [--quick]\n"
    FileHandle.standardError.write(s.data(using: .utf8)!)
    exit(1)
}

/// $HOME ではなくアカウント情報のホーム。sudo 経由で起動されても自分のホームになる
func homeDir() -> String {
    if let pw = getpwuid(getuid()), let dir = pw.pointee.pw_dir { return String(cString: dir) }
    return NSHomeDirectory()
}

func parseOptions() -> Options {
    var o = Options()
    var args = Array(CommandLine.arguments.dropFirst())
    func value() -> String {
        if args.isEmpty { usage() }
        return args.removeFirst()
    }
    while !args.isEmpty {
        switch args.removeFirst() {
        case "--kind": o.kind = value()
        case "--band": o.band = value()
        case "--day": o.day = value()
        case "--messages": o.messages = value()
        case "--text": o.text = value()
        case "--no-record": o.record = false
        case "--away":
            guard let n = Double(value()), n > 0 else { usage() }
            o.away = n
        case "--quick": o.quick = true
        default: usage()
        }
    }
    guard o.kind == "bath" || o.kind == "sleep" else { usage() }
    if o.text == nil && bandKey(o.band) == nil { usage() }
    if o.day.isEmpty {
        let f = DateFormatter(); f.dateFormat = "yyyyMMdd"
        o.day = f.string(from: Date())
    }
    if o.messages.isEmpty { o.messages = homeDir() + "/.config/sleepguard/messages.md" }
    return o
}

// MARK: - 文面ファイル

/// "22:30" や "## 0:00以降に表示" から HHMM の数値を取り出す。時刻でなければ nil
func bandKey(_ s: String) -> Int? {
    var t = Substring(s)
    if t.hasPrefix("##") { t = t.dropFirst(2) }
    t = t.drop(while: { $0 == " " || $0 == "\t" })
    let head = t.prefix(while: { ("0"..."9").contains($0) || $0 == ":" })
    let parts = head.split(separator: ":", omittingEmptySubsequences: false)
    guard parts.count == 2, (1...2).contains(parts[0].count), parts[1].count == 2,
          let h = Int(parts[0]), let m = Int(parts[1]), h < 24, m < 60 else { return nil }
    return h * 100 + m
}

/// 見出しの時刻が band の節にある「- 」の行を集める
func loadMessages(path: String, band: Int) -> [String] {
    guard let s = try? String(contentsOfFile: path, encoding: .utf8) else { return [] }
    var inBand = false
    var out: [String] = []
    for raw in s.components(separatedBy: .newlines) {
        let line = raw.trimmingCharacters(in: .whitespaces)
        if line.hasPrefix("#") {
            inBand = line.hasPrefix("##") && bandKey(line) == band
            continue
        }
        guard inBand, line.hasPrefix("- ") else { continue }
        let t = line.dropFirst(2).trimmingCharacters(in: .whitespaces)
        if !t.isEmpty { out.append(t) }
    }
    return out
}

/// その夜にまだ出していない文からランダムに選ぶ。尽きたら全部から引き直す
func pickMessage(_ pool: [String], recordFile: URL?) -> String {
    var shown: [String] = []
    if let u = recordFile, let s = try? String(contentsOf: u, encoding: .utf8) {
        shown = s.components(separatedBy: "\n").filter { !$0.isEmpty }
    }
    var rest = pool.filter { !shown.contains($0) }
    if rest.isEmpty { rest = pool; shown = [] }
    let m = rest.randomElement()!
    if let u = recordFile {
        shown.append(m)
        try? FileManager.default.createDirectory(at: u.deletingLastPathComponent(), withIntermediateDirectories: true)
        try? (shown.joined(separator: "\n") + "\n").write(to: u, atomically: true, encoding: .utf8)
    }
    return m
}

/// 3日より古い記録を消す
func cleanup(_ dir: URL) {
    let fm = FileManager.default
    guard let files = try? fm.contentsOfDirectory(at: dir, includingPropertiesForKeys: [.contentModificationDateKey]) else { return }
    let limit = Date().addingTimeInterval(-3 * 86400)
    for f in files {
        if let d = (try? f.resourceValues(forKeys: [.contentModificationDateKey]))?.contentModificationDate, d < limit {
            try? fm.removeItem(at: f)
        }
    }
}

// MARK: - 画面

/// 最初の15秒、クリックもキーも受け止めて何もしないビュー
final class BlockingView: NSView {
    override var acceptsFirstResponder: Bool { true }
    override func keyDown(with event: NSEvent) {}
    override func keyUp(with event: NSEvent) {}
    override func performKeyEquivalent(with event: NSEvent) -> Bool { true }   // Cmd+Q などを無効化
    override func mouseDown(with event: NSEvent) {}
    override func rightMouseDown(with event: NSEvent) {}
    override func otherMouseDown(with event: NSEvent) {}
}

/// 前面でないアプリの窓でも、最初のクリックで押せるボタン
final class FirstClickButton: NSButton {
    override func acceptsFirstMouse(for event: NSEvent?) -> Bool { true }
}

let behavior: NSWindow.CollectionBehavior = [.canJoinAllSpaces, .fullScreenAuxiliary, .stationary, .ignoresCycle]
let textColor = NSColor(red: 0.93, green: 0.82, blue: 0.64, alpha: 1)
let backColor = NSColor(red: 0.05, green: 0.04, blue: 0.04, alpha: 1)

/// 最後にキーボードかマウスを操作してからの秒数
func idleSeconds() -> Double {
    CGEventSource.secondsSinceLastEventType(.combinedSessionState, eventType: CGEventType(rawValue: ~0)!)
}

func font(_ size: CGFloat) -> NSFont {
    NSFont(name: "HiraMinProN-W3", size: size) ?? NSFont.systemFont(ofSize: size, weight: .light)
}

final class Controller: NSObject, NSApplicationDelegate {
    let opt: Options
    let text: String
    var overlays: [NSWindow] = []
    var backdrops: [NSView] = []
    var bars: [NSWindow] = []
    var blocking = true
    let previous = NSWorkspace.shared.frontmostApplication   // 起動前に前面だったアプリ

    init(opt: Options, text: String) {
        self.opt = opt
        self.text = text
    }

    func applicationDidFinishLaunching(_ note: Notification) {
        let (t1, t2, t3) = opt.quick ? (2.0, 4.0, 20.0) : (15.0, 30.0, 120.0)
        let away = opt.quick ? 10.0 : opt.away
        for screen in NSScreen.screens { makeOverlay(on: screen) }
        NSApp.activate(ignoringOtherApps: true)
        for w in overlays { w.makeKeyAndOrderFront(nil) }
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 1.0
            for w in self.overlays { w.animator().alphaValue = 1 }
        }
        NotificationCenter.default.addObserver(self, selector: #selector(stayInFront),
                                               name: NSApplication.didResignActiveNotification, object: nil)
        DispatchQueue.main.asyncAfter(deadline: .now() + t1) { self.letThrough() }
        DispatchQueue.main.asyncAfter(deadline: .now() + t2) { self.showBars() }
        if opt.kind == "bath" {
            // 入浴の画面は自動では閉じない。保存などを済ませてお風呂へ行くまで残す。
            // 離席が away 秒に達したら、お風呂に行ったとみなして閉じる
            Timer.scheduledTimer(withTimeInterval: 5, repeats: true) { _ in
                if idleSeconds() >= away { exit(3) }
            }
        } else {
            DispatchQueue.main.asyncAfter(deadline: .now() + t3) { exit(0) }
        }
    }

    func makeOverlay(on screen: NSScreen) {
        let frame = screen.frame
        let w = NSWindow(contentRect: frame, styleMask: .borderless, backing: .buffered, defer: false, screen: screen)
        w.level = .screenSaver
        w.isOpaque = false
        w.backgroundColor = .clear
        w.hasShadow = false
        w.collectionBehavior = behavior
        w.isReleasedWhenClosed = false
        w.alphaValue = 0

        let root = BlockingView(frame: NSRect(origin: .zero, size: frame.size))
        root.wantsLayer = true

        let backdrop = NSView(frame: root.bounds)
        backdrop.wantsLayer = true
        backdrop.layer?.backgroundColor = backColor.cgColor
        backdrop.autoresizingMask = [.width, .height]
        root.addSubview(backdrop)

        // 文面: 画面の高さから文字の大きさを決め、幅の75%で折り返して中央に置く
        let size = max(28, frame.height / 18)
        let maxW = frame.width * 0.75
        let para = NSMutableParagraphStyle()
        para.alignment = .center
        para.lineHeightMultiple = 1.5
        let label = NSTextField(wrappingLabelWithString: "")
        label.attributedStringValue = NSAttributedString(string: text, attributes: [
            .font: font(size), .foregroundColor: textColor, .paragraphStyle: para,
        ])
        label.isSelectable = false
        label.preferredMaxLayoutWidth = maxW
        let h = label.cell!.cellSize(forBounds: NSRect(x: 0, y: 0, width: maxW, height: 100_000)).height
        label.frame = NSRect(x: (frame.width - maxW) / 2, y: (frame.height - h) / 2 + size * 0.6, width: maxW, height: h)
        root.addSubview(label)

        // 現在時刻
        let f = DateFormatter(); f.dateFormat = "H:mm"
        let clock = NSTextField(labelWithString: f.string(from: Date()))
        clock.font = font(size * 0.45)
        clock.textColor = NSColor(white: 0.5, alpha: 1)
        clock.sizeToFit()
        clock.frame.origin = NSPoint(x: (frame.width - clock.frame.width) / 2, y: label.frame.minY - size * 1.6)
        root.addSubview(clock)

        w.contentView = root
        w.makeFirstResponder(root)
        overlays.append(w)
        backdrops.append(backdrop)
    }

    /// 最初の15秒は、ほかのアプリに切り替えられても前面に戻る
    @objc func stayInFront() {
        guard blocking else { return }
        NSApp.activate(ignoringOtherApps: true)
        for w in overlays { w.makeKeyAndOrderFront(nil) }
    }

    /// 15秒後: 背景を半透明にし、クリックもキーも下のアプリへ通す
    func letThrough() {
        blocking = false
        NSAnimationContext.runAnimationGroup { ctx in
            ctx.duration = 0.8
            for b in self.backdrops { b.animator().alphaValue = 0.4 }
        }
        for w in overlays { w.ignoresMouseEvents = true }
        if let prev = previous, prev.processIdentifier != ProcessInfo.processInfo.processIdentifier {
            if #available(macOS 14.0, *) { NSApp.yieldActivation(to: prev) }
            prev.activate(options: [])
        } else {
            NSApp.deactivate()
        }
    }

    /// 30秒後: 画面下にボタンのバーを出す
    func showBars() {
        let bath = opt.kind == "bath"
        let width: CGFloat = 172
        let height: CGFloat = 60
        for screen in NSScreen.screens {
            let frame = NSRect(x: screen.frame.midX - width / 2, y: screen.frame.minY + 56, width: width, height: height)
            // nonactivatingPanel: 押しても自分が前面にならないので、下のアプリの操作を邪魔しない
            let p = NSPanel(contentRect: frame, styleMask: [.borderless, .nonactivatingPanel], backing: .buffered, defer: false)
            p.level = NSWindow.Level(rawValue: NSWindow.Level.screenSaver.rawValue + 1)
            p.isOpaque = false
            p.backgroundColor = .clear
            p.hasShadow = true
            p.collectionBehavior = behavior
            p.isReleasedWhenClosed = false
            p.appearance = NSAppearance(named: .darkAqua)

            let v = NSView(frame: NSRect(origin: .zero, size: frame.size))
            v.wantsLayer = true
            v.layer?.backgroundColor = NSColor(red: 0.12, green: 0.10, blue: 0.10, alpha: 0.94).cgColor
            v.layer?.cornerRadius = 14
            // 入浴の画面に「閉じる」は無い。お風呂に行くまで残し、押せるのは「入浴済み」だけ
            let b = bath ? button("入浴済み", #selector(bathDone)) : button("閉じる", #selector(closeIt))
            b.frame = NSRect(x: 16, y: 14, width: 140, height: 32)
            v.addSubview(b)
            p.contentView = v
            p.alphaValue = 0
            p.orderFront(nil)
            p.animator().alphaValue = 1
            bars.append(p)
        }
    }

    func button(_ title: String, _ action: Selector) -> NSButton {
        let b = FirstClickButton(title: title, target: self, action: action)
        b.bezelStyle = .rounded
        b.font = NSFont.systemFont(ofSize: 15)
        return b
    }

    @objc func closeIt() { exit(0) }
    @objc func bathDone() { exit(2) }
}

// MARK: - 起動

let opt = parseOptions()
let text: String
if let t = opt.text {
    text = t
} else {
    let key = bandKey(opt.band)!
    let pool = loadMessages(path: opt.messages, band: key)
    if pool.isEmpty {
        text = opt.kind == "bath" ? "区切りをつけて、お風呂に入りましょう。" : "もう寝ましょう。"
    } else {
        var record: URL? = nil
        if opt.record {
            let dir = URL(fileURLWithPath: homeDir()).appendingPathComponent(".config/sleepguard/shown")
            cleanup(dir)
            record = dir.appendingPathComponent("\(opt.day)-\(String(format: "%04d", key)).txt")
        }
        text = pickMessage(pool, recordFile: record)
    }
}
print("shown: \(text)")

let app = NSApplication.shared
app.setActivationPolicy(.accessory)
let controller = Controller(opt: opt, text: text)
app.delegate = controller
app.run()
