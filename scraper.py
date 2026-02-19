"""
Song Audio Scraper
==================
Uses yt-dlp to search YouTube for a song by name and download it as a
high-quality audio file (MP3 or Opus) via ffmpeg post-processing.

Quality features:
  - Prefers the highest-bitrate source audio stream (opus/m4a)
  - MP3: up to 320 kbps CBR, full stereo
  - Opus: transparent quality at 128 kbps (~60% smaller than 320kbps MP3)
  - Embeds album-art thumbnail and metadata (title, artist, etc.)
  - Concurrent fragment downloads for maximum speed
"""

import os
import re
import time
import logging
from pathlib import Path
from typing import Optional

import yt_dlp

logger = logging.getLogger(__name__)


class SongScraper:
    """Search for songs on YouTube and download them as MP3 files."""

    # Maximum video duration (seconds) to avoid albums/podcasts
    MAX_DURATION = 900  # 15 minutes

    # Number of search results to evaluate
    SEARCH_COUNT = 10

    # Keywords that indicate a result is likely just the audio track
    AUDIO_KEYWORDS = re.compile(
        r"(official\s*audio|lyrics?\s*video|audio|lyric|official\s*music\s*video)",
        re.IGNORECASE,
    )

    # Keywords that indicate a result is NOT what we want
    REJECT_KEYWORDS = re.compile(
        r"(live\s*performance|concert|reaction|cover\s*by|tutorial|karaoke|remix|slowed|reverb|sped\s*up|bass\s*boosted|instrumental|behind\s*the\s*scenes|interview|making\s*of|drum\s*cover|guitar\s*cover|piano\s*cover)",
        re.IGNORECASE,
    )

    def __init__(self, quality: int = 320, codec: str = "mp3"):
        """
        Args:
            quality: Audio bitrate in kbps.
                     MP3 valid values: 128, 192, 256, 320.
                     Opus valid values: 64, 96, 128, 160 (128 recommended).
            codec:   'mp3' or 'opus'.
        """
        if codec not in ("mp3", "opus"):
            raise ValueError(f"Invalid codec '{codec}'. Choose 'mp3' or 'opus'.")
        if codec == "mp3" and quality not in (128, 192, 256, 320):
            raise ValueError(f"Invalid MP3 quality {quality}. Choose 128, 192, 256, or 320.")
        if codec == "opus" and quality not in (64, 96, 128, 160, 192):
            raise ValueError(f"Invalid Opus quality {quality}. Choose 64, 96, 128, or 160.")
        self.quality = quality
        self.codec = codec

    # ------------------------------------------------------------------
    # Search
    # ------------------------------------------------------------------

    def search(self, query: str) -> dict:
        """
        Search YouTube for the best-matching video for the given song name.

        Args:
            query: Song name, optionally including the artist.

        Returns:
            dict with keys: id, title, url, duration, uploader, thumbnail
        """
        ydl_opts = {
            "quiet": True,
            "no_warnings": True,
            "extract_flat": False,
            "default_search": f"ytsearch{self.SEARCH_COUNT}",
            "noplaylist": True,
            "skip_download": True,
        }

        logger.info("Searching YouTube for: %s", query)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(query, download=False)

        entries = info.get("entries", [])
        if not entries:
            raise LookupError(f"No results found for '{query}'")

        best = self._pick_best(entries, query)

        return {
            "id": best["id"],
            "title": best.get("title", "Unknown"),
            "url": best.get("webpage_url") or f"https://www.youtube.com/watch?v={best['id']}",
            "duration": best.get("duration", 0),
            "uploader": best.get("uploader", "Unknown"),
            "thumbnail": best.get("thumbnail", ""),
        }

    def _pick_best(self, entries: list[dict], query: str) -> dict:
        """
        Rank search results and pick the best match.

        Scoring logic:
          +3  if title contains an "audio" keyword (official audio, lyrics, etc.)
          -10 if title contains a "reject" keyword (cover, remix, live, etc.)
          +1  if duration is within a typical song range (1:30 – 7:00)
          -5  if duration exceeds MAX_DURATION

        Falls back to the first entry if every result scores poorly.
        """
        scored: list[tuple[int, dict]] = []

        for entry in entries:
            if entry is None:
                continue

            title = entry.get("title", "")
            duration = entry.get("duration") or 0
            score = 0

            # Prefer "official audio" style results
            if self.AUDIO_KEYWORDS.search(title):
                score += 3

            # Penalise covers, remixes, live recordings, etc.
            if self.REJECT_KEYWORDS.search(title):
                score -= 10

            # Prefer typical song duration (90s – 420s)
            if 90 <= duration <= 420:
                score += 1

            # Hard-penalise very long videos
            if duration > self.MAX_DURATION:
                score -= 5

            scored.append((score, entry))

        # Sort by score descending, then by original order
        scored.sort(key=lambda x: x[0], reverse=True)

        best_score, best_entry = scored[0]
        logger.info(
            "Selected: '%s' (score=%d, duration=%ds)",
            best_entry.get("title"),
            best_score,
            best_entry.get("duration", 0),
        )
        return best_entry

    # ------------------------------------------------------------------
    # Download
    # ------------------------------------------------------------------

    def download(
        self,
        url: str,
        output_dir: str | Path = "downloads",
        progress_hook=None,
    ) -> Path:
        """
        Download audio from a URL and convert to MP3 or Opus.

        Args:
            url:           YouTube video URL.
            output_dir:    Directory to save the audio file.
            progress_hook: Optional callable(dict) for progress updates.

        Returns:
            Path to the saved audio file (.mp3 or .opus).
        """
        output_dir = Path(output_dir)
        output_dir.mkdir(parents=True, exist_ok=True)

        outtmpl = str(output_dir / "%(title).200B.%(ext)s")

        if self.codec == "opus":
            # ── Opus path: keep native opus stream when available ────
            # YouTube already encodes in opus/webm; we re-encode to a
            # standalone .opus container at the target bitrate.
            postprocessors = [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "opus",
                    "preferredquality": str(self.quality),
                },
                {"key": "FFmpegMetadata", "add_metadata": True},
            ]
            # Opus bitrate via libopus (VBR for best quality/size ratio)
            pp_args = {
                "extractaudio": ["-b:a", f"{self.quality}k", "-vbr", "on"],
            }
        else:
            # ── MP3 path (original, max-quality) ────────────────────
            postprocessors = [
                {
                    "key": "FFmpegExtractAudio",
                    "preferredcodec": "mp3",
                    "preferredquality": str(self.quality),
                },
                {"key": "FFmpegMetadata", "add_metadata": True},
                {"key": "EmbedThumbnail", "already_have_thumbnail": False},
            ]
            # Force CBR + full stereo for MP3
            pp_args = {
                "extractaudio": ["-b:a", f"{self.quality}k", "-joint_stereo", "0"],
            }

        ydl_opts = {
            "format": "bestaudio[asr>=44100]/bestaudio/best",
            "format_sort": ["abr", "asr"],
            "outtmpl": outtmpl,
            "noplaylist": True,
            "quiet": True,
            "no_warnings": True,
            "concurrent_fragment_downloads": 4,
            "buffersize": 1024 * 64,
            "writethumbnail": True,
            "postprocessors": postprocessors,
            "postprocessor_args": pp_args,
            "restrictfilenames": True,
            "windowsfilenames": True,
        }

        if progress_hook:
            ydl_opts["progress_hooks"] = [progress_hook]

        logger.info("Downloading [%s %skbps]: %s -> %s", self.codec, self.quality, url, output_dir)

        with yt_dlp.YoutubeDL(ydl_opts) as ydl:
            info = ydl.extract_info(url, download=True)

        title = info.get("title", "audio")
        audio_path = self._find_audio(output_dir, self.codec)

        if audio_path is None:
            raise FileNotFoundError(
                f"Download succeeded but no .{self.codec} found in {output_dir}"
            )

        logger.info("Saved: %s (%s)", audio_path, _human_size(audio_path.stat().st_size))
        return audio_path

    # ------------------------------------------------------------------
    # Combined convenience method
    # ------------------------------------------------------------------

    def search_and_download(
        self,
        query: str,
        output_dir: str | Path = "downloads",
        progress_hook=None,
    ) -> tuple[dict, Path]:
        """
        Search for a song and download it in one call.

        Returns:
            (metadata_dict, path_to_audio)
        """
        metadata = self.search(query)
        audio_path = self.download(
            url=metadata["url"],
            output_dir=output_dir,
            progress_hook=progress_hook,
        )
        return metadata, audio_path

    # ------------------------------------------------------------------
    # Helpers
    # ------------------------------------------------------------------

    @staticmethod
    def _find_audio(directory: Path, codec: str) -> Optional[Path]:
        """
        Find the most recently created audio file matching the codec extension.
        Opus files may also arrive as .ogg; try both.
        """
        extensions = [codec]
        if codec == "opus":
            extensions.append("ogg")  # yt-dlp sometimes outputs .ogg for opus
        for ext in extensions:
            files = list(directory.glob(f"*.{ext}"))
            if files:
                return max(files, key=lambda p: p.stat().st_mtime)
        return None


def _human_size(nbytes: int) -> str:
    """Format byte count as human-readable string."""
    for unit in ("B", "KB", "MB", "GB"):
        if nbytes < 1024:
            return f"{nbytes:.1f} {unit}"
        nbytes /= 1024
    return f"{nbytes:.1f} TB"
