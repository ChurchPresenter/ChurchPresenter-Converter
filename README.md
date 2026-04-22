# ChurchPresenter Converter

A cross-platform desktop app for converting church presentation files to [ChurchPresenter](https://github.com/user/ChurchPresenter) formats.

Built with Kotlin Multiplatform and Compose Desktop.

## Features

### Song Converters

- **SNG to SONG** — Convert SongBeamer `.sng` files to `.song` format
  - Supports UTF-8 and Windows-1251 encoded files
  - Preserves verse order metadata
  - Batch conversion (select files or entire folder)

- **SPS to SONG** — Convert SongPresenter `.sps` songbooks to individual `.song` files
  - Text-based `.sps` format (Windows SongPresenter)
  - SQLite `.sps` format (Mac SongPresenter)
  - Extracts songbook name, song metadata (author, composer, tune), and lyrics
  - Automatically structures chorus/verse sections

### Bible Converter

- **XML to SPB** — Convert Zefania XML bible files to `.spb` format
  - Supports 60+ languages with localized book names
  - Handles right-to-left languages (Arabic, Hebrew, Syriac)
  - Batch conversion with recursive folder scanning

### General

- Preview before converting — see exactly what will happen before committing
- Overwrite warnings for existing output files
- Dark theme UI

## Download

Download the latest release from the [Releases](../../releases) page.

## Build from Source

### Requirements

- JDK 21+

### Run

```bash
./gradlew run
```

### Package

```bash
# Windows installer
./gradlew packageMsi

# macOS
./gradlew packageDmg

# Linux
./gradlew packageDeb
```

## File Formats

### .sng (SongBeamer)

Text file with `#Key=Value` headers and `---`-separated verse sections.

### .sps (SongPresenter)

Either a text file with `#$#`-delimited song entries or a SQLite database. Contains an entire songbook of songs.

### .song (ChurchPresenter)


### .xml (Zefania XML Bible)

Standard Zefania XML Bible Markup with `BIBLEBOOK > CHAPTER > VERS` structure.

### .spb (ChurchPresenter Bible)

Tab-separated text file with header metadata and `B001C001V001` verse identifiers.

## License

MIT
