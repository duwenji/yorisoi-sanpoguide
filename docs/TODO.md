# TODO

SanpoGuide で残っている作業と、決めること。できている機能は [README](../README.md)、構造は [architecture.md](architecture.md) にある。エコシステム全体の作業（第三者のチャンネルの審査、秘密メッセージの契約、サーバーの要否・収益化など）は [エコシステム TODO](https://github.com/duwenji/yorisoi-platform/blob/main/docs/work/ecosystem-todo.md) にある。

## 優先度の目安

| 記号 | 意味 |
|---|---|
| ★ | 次に着手したい |
| ☆ | いずれ着手したい |
| ？ | アイデア段階（やるかどうか未定） |

## 一覧

| 優先度 | 項目 | 概要 |
|---|---|---|
| ★ | [Google Play での公開](#google-play-での公開) | targetSdk 36、ストアの登録、プライバシー、クローズドテスト |
| ★ | [実際に歩いての確認](#実際に歩いての確認) | 安全・施設の案内、雰囲気、背景音、チャンネルを実機で確かめ、既定値を見直す |
| ★ | [チャンネル](#チャンネル) | 試用チケットでの通しの確認、通知からの切り替え |
| ☆ | [位置に紐づく秘密メッセージ](#位置に紐づく秘密メッセージ) | 特定の場所に来たときだけ読める・聞けるメッセージ |
| ☆ | [見た目と音の改善](#見た目と音の改善) | 写真の出し方、絵・背景音の作り込み |
| ？ | [その他の候補](#その他の候補) | 散策ルートの提案、写真メモなど |

---

## Google Play での公開

- [x] targetSdk と compileSdk を 36 にする（新しいアプリと更新に必須）。向きの固定・edge-to-edge の無効化はしていないので影響なし。エミュレータ（Android 17）で、メイン・設定・戻る操作・散策の開始（位置情報のフォアグラウンドサービス）を確認
- [ ] リリースは AAB（`./gradlew bundleRelease`）で出す。署名の鍵（`release.jks`）は Play App Signing のアップロード鍵にする
- [x] バックアップしない設定を Android 12 以上にも広げる（`dataExtractionRules`。端末間の移行も含めない）
- [x] プライバシーポリシー（[privacy-policy.md](privacy-policy.md)）を GitHub Pages で公開する: https://duwenji.github.io/yorisoi-sanpoguide/privacy-policy
- [ ] Play Console のデータセーフティ欄と、フォアグラウンドサービス（位置情報）の申告を入れる（答えは [play-console.md](play-console.md)。申告の動画を撮る）
- [ ] ストアの素材: 512×512 のアイコン、1024×500 のフィーチャー画像、スクリーンショットを作る（内容と説明文は [play-console.md](play-console.md#ストアの掲載情報)）
- [ ] ストアの設定で決めること: 外部マーケティング（Google が Play の外で宣伝してよいか）、公開する国・地域（日本語だけなので日本だけが候補）、有料か無料か（無料から有料には戻せない。有料ならお支払いプロファイルと価格の設定が要る）
- [ ] 個人のデベロッパーアカウントなら、本番の前に 12 人以上・14 日以上のクローズドテスト

最初の公開は内蔵のチャンネルだけ。設定の「配信されているチャンネル・リスト」は開発者モードのときだけ出す。第三者のチャンネルを開くときは、管理システムの本番と内蔵のリストを用意し、ボタンを誰にでも出し、プライバシーポリシーの「チャンネル・リストの発行元」を直す

## 実際に歩いての確認

天気の急変・日の入り・施設の話しかけは、条件がそろわずエミュレータでは流れていない。

- [ ] 安全・施設の案内が実際に流れるか。しきい値の既定値（今は仮）を見直す。話しかけの頻度ごとに既定値を変えるかも検討
- [ ] 雷の予告の通知を、強い雨にも出すか
- [ ] 雰囲気: 場所の種類の推定が合っているか（とくに「史跡」: 石碑などの `historic` が多い場所では、寺社の近くでも史跡と判定されやすい）。AI のトーンの変わり方
- [ ] 背景音: 実機のイヤホン（有線・Bluetooth）での音質・音量・切り替えのフェード、イヤホンを外したときの停止、他のアプリで音楽を再生したときの停止。電池の消費（22,050Hz・モノラルの合成を 50ms ごと）
- [ ] チャンネル: 各チャンネルの語り口、優先する話題の加点（+2）、きっかけの出方。グルメの旅・ショッピングの店の検索
- [ ] Overpass の失敗の頻度を集計し、自前・商用のサーバーを検討する材料にする（[エコシステム TODO](https://github.com/duwenji/yorisoi-platform/blob/main/docs/work/ecosystem-todo.md#共通の検討事項)の「公開サーバーの負荷」）。Overpass の予備のサーバーの証明書が Android 11 で信頼されない件も

## チャンネル

設計は [channels.md](channels.md)、形式は [platform API-CHANNEL-PACKAGE（旧 API-003）](https://github.com/duwenji/yorisoi-platform/blob/main/docs/contracts/API-CHANNEL-PACKAGE.md)、第三者のチャンネルの方針は [platform ADR-CHANNEL-CURATION（旧 ADR-001）](https://github.com/duwenji/yorisoi-platform/blob/main/docs/decisions/ADR-CHANNEL-CURATION.md)・[platform ADR-MATERIAL-PRODUCERS（旧 ADR-002）](https://github.com/duwenji/yorisoi-platform/blob/main/docs/decisions/ADR-MATERIAL-PRODUCERS.md)、関係者の呼び方は [関係者と共通用語](https://github.com/duwenji/yorisoi-platform/blob/main/docs/overview/roles-and-vocabulary.md)。

- [ ] 試用チケットで、素材集・基準を使うチャンネルを通しで確かめる（チャンネル提供者の鍵ファイルが届いてから）。カメラで QR を写す確認は実機で
- [ ] 通知からの切り替え（channels.md の未決事項 No.1。歩いてから決める）

第三者のチャンネル・素材集を審査して公開するのは、アプリとは別の管理システム（[yorisoi-console](https://github.com/duwenji/yorisoi-console)）。アプリとの境界は [platform API-CHANNEL-LIST（旧 API-002）](https://github.com/duwenji/yorisoi-platform/blob/main/docs/contracts/API-CHANNEL-LIST.md) だけ。管理システムの作業は yorisoi-console の docs/TODO.md、関係者ごとの対応案と審査の運用はエコシステム TODO にある。

## 位置に紐づく秘密メッセージ

特定の場所に来たときだけ読める・聞けるメッセージ。ジオキャッシュのように、場所に「置いて」おき、その場所に来た人が見つける。

利用場面の例:
- 自分宛て: 「1 年後にまたここに来たら読む」メッセージ
- 家族・友人宛て: 特定の人だけが開けるメッセージ
- 公開: その場所を訪れた人なら誰でも見つけられる（宝探し、イベント）
- チャンネルと組み合わせた、店舗やイベントの隠しコンテンツ

自分宛てだけならサーバーなしで作れる（`HistoryStore` と同じく端末内に保存）。先にここだけ切り出して着手できる。他人宛て・グループ・公開のサーバー API は yorisoi-platform の [API-SECRET-MESSAGES（旧 API-001）](https://github.com/duwenji/yorisoi-platform/blob/main/docs/contracts/API-SECRET-MESSAGES.md) で決めた。接続先は URL で設定し、API に準拠していればサーバーの実装方法・置き場所は問わない。準拠テスト・参照実装のサーバーと、位置の偽装・不適切な投稿への対策は [エコシステム TODO](https://github.com/duwenji/yorisoi-platform/blob/main/docs/work/ecosystem-todo.md#位置に紐づく秘密メッセージ) にある。

- [ ] メッセージを置く（現在地、または地図上の地点を指定）
- [ ] 開ける条件（半径◯m 以内、日時・期間、特定の相手、合言葉）
- [ ] 近くに来たら通知し、話しかけで知らせる
- [ ] 形式（テキスト・音声・写真）
- [ ] アプリの実装（設定画面の「メッセージサーバー」欄、鍵の作成と QR コードの交換、送受信）

## 見た目と音の改善

- [ ] 散策中に案内したスポットの写真を、カードの絵の代わりに出すかどうか（画面を見させない方針とのかね合い）
- [ ] 描画の絵の見た目（今は図形の組み合わせで素朴。イラストを描き起こして同梱するかは、見た目への要望次第）
- [ ] 明朝体（寺社・史跡の近く）がアプリ全体（設定画面を含む）にかかる。解説とカードだけに絞るか
- [ ] 合成の背景音が安っぽければ、ライセンスの明確な録音素材（OGG のループ）に置き換える。`Soundscape` と選び方はそのまま使える
- [ ] 水辺の判定に海岸線（`natural=coastline`）を使うか（形が大きく検索が重い。今は名前のある砂浜と水面だけ）

## その他の候補

- [x] 散策前のプラン入力（テーマ・時間・距離）と、周辺スポットからの立ち寄り候補の提案・選択
- [ ] 道路に沿った散策ルートの提案・最適化（時間・距離は目安で、達成を保証しない）
- [ ] 写真・メモを散歩の記録に残す
- [ ] 散歩の記録から「思い出」を振り返る（1 年前の今日に歩いた場所など）
- [ ] 熱中症・寒さへの注意（気温・湿度が高いとき、歩いた時間に応じて休憩や水分補給を促す。施設案内の「水分補給」と組み合わせる）

## 共通の検討事項

- **プライバシー**: 新機能で外部送信する情報の範囲が変わる場合は、README とプライバシーポリシーを実装に合わせて更新し、同意を取る
- **安全**: 歩きながら画面を見させない。音声中心の案内を基本にする
- **電池・通信量**: 画像・音声の取得と再生が増えるため、散策モードでの消費を計測する

サーバーの要否・収益化・公開サーバーの負荷は、エコシステム全体で決める（[yorisoi-platform の TODO](https://github.com/duwenji/yorisoi-platform/blob/main/docs/work/ecosystem-todo.md#共通の検討事項)）。
