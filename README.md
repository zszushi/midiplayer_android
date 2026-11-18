# Obsidian Midi

数百万〜数億ノーツ規模の巨大な Black MIDI ファイルを、メモリに全展開せずストリーム再生できる Android 向け超軽量 MIDI プレイヤー＆ゼロGCシンセサイザーです。

## 主な特徴

- **即時ストリーム準備＆マルチコア並列トラック解析**: `MTrk` チャンク長ジャンプによる数ミリ秒での即時先行再生と、`n / m トラック解析済み`・走査速度（MB/s）・検出ノーツ数のリアルタイム表示、2回目以降のバイナリインデックスキャッシュ復元に対応。
- **Kotlin ゼロGC 音声合成エンジン**: Structure-of-Arrays（プリミティブ配列）ボイスプール＋ロックフリー SPSC リングバッファ＋低遅延 `AudioTrack` 専用スレッドで駆動。
- **3系統の音源対応**:
  - 内蔵サイン波（Pure Sine LUT / 倍音ブレンド切替付）
  - **SF2 (`.sf2`)**: `smpl` 波形チャンクをメモリマップ共有し、大容量 SoundFont も省メモリで再生
  - **SFZ (`.sfz`)**: `.sfz` と `.wav` サンプル群を含む親フォルダを選択するだけで自動解決
- **Black MIDI 負荷制御＆横画面対応**: 最大同時発音数（64〜4,096）、ベロシティ足切り閾値、同一キー最大重なり数（高速フェードスティーリング）、ハードリミッター、および「落下ノーツ＋88鍵盤モード」と「超軽量統計モード」のワンタップ切替を搭載。

---

## GitHub Actions での APK 自動ビルド手順

このリポジトリには `.github/workflows/android-build.yml` が構成済みのため、**AI Studio から GitHub にプッシュするだけで自動的に署名済み APK がビルドされます**（手元の PC や Android Studio は不要です）。

### 1. リポジトリへプッシュ
AI Studio のメニューから GitHub リポジトリへエクスポート（Push）します。`main` または `master` ブランチへのプッシュと同時に **Actions** タブで自動ビルドが開始されます。

### 2. スマホで一番ラクに `.apk` を直接ダウンロードする方法（ZIP解凍不要）
GitHub Actions の通常の Artifacts は `.zip` 形式で固められますが、**GitHub Releases** を使うと生の `.apk` ファイルをワンタップでダウンロードできます：

1. GitHub リポジトリの **Actions** タブを開く
2. 左側（または一覧）から **Build Obsidian Midi APK** を選択
3. **Run workflow** ボタンを押して実行（`publish_github_release` がデフォルトで ON になっています）
4. ビルド完了後、リポジトリの **Releases** ページに `ObsidianMidi-release.apk` と `ObsidianMidi-debug.apk` が公開されるため、スマホのブラウザからそのままタップしてインストールできます。

### カスタム署名鍵を使う場合（任意）
未設定でも CI 上で自動的に一時署名鍵を生成してインストール可能な Release APK を出力しますが、独自の署名鍵で固定したい場合は GitHub リポジトリの **Settings > Secrets and variables > Actions** に以下を登録してください：
- `RELEASE_KEYSTORE_BASE64`: `.jks` キーストアを Base64 化した文字列（エイリアス名 `upload`）
- `STORE_PASSWORD`: キーストアのパスワード
- `KEY_PASSWORD`: 鍵のパスワード
