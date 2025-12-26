#!/usr/bin/env python3
"""Scrape Instagram profiles and posts into a SQLite database."""
from __future__ import annotations

import argparse
import logging
import os
import sqlite3
import sys
import time
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Dict, Iterable, List, Optional, Tuple

import pandas as pd
import requests

DEFAULT_USER_AGENT = (
    "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
    "AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"
)
WEB_PROFILE_URL = (
    "https://www.instagram.com/api/v1/users/web_profile_info/?username={username}"
)
USER_FEED_URL = "https://www.instagram.com/api/v1/feed/user/{user_id}/"
WEB_APP_ID = "936619743392459"


@dataclass
class Profile:
    username: str
    full_name: str
    biography: str
    profile_pic_url: str
    followers: Optional[int]
    following: Optional[int]
    media_count: Optional[int]
    external_url: Optional[str]
    category_name: Optional[str]
    is_private: bool
    is_verified: bool


@dataclass
class Post:
    post_id: str
    shortcode: str
    caption: str
    thumbnail_url: str
    display_url: str
    permalink: str
    is_video: bool
    video_view_count: Optional[int]
    like_count: Optional[int]
    comment_count: Optional[int]
    taken_at: Optional[str]
    media_type: str


class Database:
    def __init__(self, path: str) -> None:
        self.conn = sqlite3.connect(path)
        self.conn.execute("PRAGMA foreign_keys = ON")
        self._create_tables()

    def _create_tables(self) -> None:
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS profiles (
                username TEXT PRIMARY KEY,
                full_name TEXT,
                biography TEXT,
                profile_pic_url TEXT,
                followers INTEGER,
                following INTEGER,
                media_count INTEGER,
                external_url TEXT,
                category_name TEXT,
                is_private INTEGER,
                is_verified INTEGER,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP
            )
            """
        )
        self.conn.execute(
            """
            CREATE TABLE IF NOT EXISTS posts (
                post_id TEXT PRIMARY KEY,
                username TEXT NOT NULL,
                shortcode TEXT,
                caption TEXT,
                thumbnail_url TEXT,
                display_url TEXT,
                permalink TEXT,
                is_video INTEGER,
                video_view_count INTEGER,
                like_count INTEGER,
                comment_count INTEGER,
                taken_at TEXT,
                media_type TEXT,
                updated_at TEXT DEFAULT CURRENT_TIMESTAMP,
                FOREIGN KEY (username) REFERENCES profiles(username) ON DELETE CASCADE
            )
            """
        )
        self.conn.commit()

    def upsert_profile(self, profile: Profile) -> None:
        self.conn.execute(
            """
            INSERT INTO profiles (
                username, full_name, biography, profile_pic_url, followers,
                following, media_count, external_url, category_name,
                is_private, is_verified, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(username) DO UPDATE SET
                full_name=excluded.full_name,
                biography=excluded.biography,
                profile_pic_url=excluded.profile_pic_url,
                followers=excluded.followers,
                following=excluded.following,
                media_count=excluded.media_count,
                external_url=excluded.external_url,
                category_name=excluded.category_name,
                is_private=excluded.is_private,
                is_verified=excluded.is_verified,
                updated_at=CURRENT_TIMESTAMP
            """,
            (
                profile.username,
                profile.full_name,
                profile.biography,
                profile.profile_pic_url,
                profile.followers,
                profile.following,
                profile.media_count,
                profile.external_url,
                profile.category_name,
                int(profile.is_private),
                int(profile.is_verified),
            ),
        )
        self.conn.commit()

    def upsert_posts(self, username: str, posts: Iterable[Post]) -> None:
        self.conn.executemany(
            """
            INSERT INTO posts (
                post_id, username, shortcode, caption, thumbnail_url, display_url,
                permalink, is_video, video_view_count, like_count, comment_count,
                taken_at, media_type, updated_at
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, CURRENT_TIMESTAMP)
            ON CONFLICT(post_id) DO UPDATE SET
                username=excluded.username,
                shortcode=excluded.shortcode,
                caption=excluded.caption,
                thumbnail_url=excluded.thumbnail_url,
                display_url=excluded.display_url,
                permalink=excluded.permalink,
                is_video=excluded.is_video,
                video_view_count=excluded.video_view_count,
                like_count=excluded.like_count,
                comment_count=excluded.comment_count,
                taken_at=excluded.taken_at,
                media_type=excluded.media_type,
                updated_at=CURRENT_TIMESTAMP
            """,
            [
                (
                    post.post_id,
                    username,
                    post.shortcode,
                    post.caption,
                    post.thumbnail_url,
                    post.display_url,
                    post.permalink,
                    int(post.is_video),
                    post.video_view_count,
                    post.like_count,
                    post.comment_count,
                    post.taken_at,
                    post.media_type,
                )
                for post in posts
            ],
        )
        self.conn.commit()

    def close(self) -> None:
        self.conn.close()


class InstagramScraper:
    def __init__(
        self,
        sessionid: str,
        *,
        user_agent: str = DEFAULT_USER_AGENT,
        timeout: int = 30,
        max_posts: int = 18,
    ) -> None:
        if not sessionid:
            raise ValueError("Instagram sessionid cookie is required")
        self.session = requests.Session()
        self.session.headers.update(
            {
                "User-Agent": user_agent,
                "Accept": "application/json, text/plain, */*",
                "X-Requested-With": "XMLHttpRequest",
                "X-IG-App-ID": WEB_APP_ID,
                "Referer": "https://www.instagram.com/",
            }
        )
        self.session.cookies.set("sessionid", sessionid)
        self.timeout = timeout
        self.max_posts = max_posts

    def fetch_user(self, username: str) -> Tuple[Profile, List[Post]]:
        url = WEB_PROFILE_URL.format(username=username)
        headers = {"Referer": f"https://www.instagram.com/{username}/"}
        response = self.session.get(url, headers=headers, timeout=self.timeout)
        if response.status_code == 404:
            raise ValueError(f"User {username} not found")
        response.raise_for_status()
        try:
            payload = response.json()
        except ValueError as exc:  # pragma: no cover - network variability
            snippet = response.text[:200].strip()
            raise ValueError(
                f"Instagram returned non-JSON response (status {response.status_code}): {snippet}"
            ) from exc
        user = self._extract_user(payload)
        profile = self._parse_profile(user)
        posts = self._fetch_posts(user, username)
        return profile, posts

    def _extract_user(self, payload: Dict) -> Dict:
        if "graphql" in payload and "user" in payload["graphql"]:
            return payload["graphql"]["user"]
        if "data" in payload and "user" in payload["data"]:
            return payload["data"]["user"]
        raise ValueError("Unexpected Instagram response structure; user node missing")

    def _parse_profile(self, user: Dict) -> Profile:
        return Profile(
            username=user.get("username", ""),
            full_name=user.get("full_name", ""),
            biography=user.get("biography", ""),
            profile_pic_url=user.get("profile_pic_url_hd")
            or user.get("profile_pic_url", ""),
            followers=_safe_get(user, "edge_followed_by", "count"),
            following=_safe_get(user, "edge_follow", "count"),
            media_count=user.get("edge_owner_to_timeline_media", {})
            .get("count"),
            external_url=user.get("external_url"),
            category_name=user.get("category_name"),
            is_private=bool(user.get("is_private")),
            is_verified=bool(user.get("is_verified")),
        )

    def _fetch_posts(self, user: Dict, username: str) -> List[Post]:
        user_id = user.get("id")
        if not user_id:
            logging.debug("Instagram user %s missing id field", username)
            return []
        url = USER_FEED_URL.format(user_id=user_id)
        headers = {"Referer": f"https://www.instagram.com/{username}/"}
        params = {"count": self.max_posts}
        response = self.session.get(
            url, headers=headers, params=params, timeout=self.timeout
        )
        if response.status_code == 404:
            logging.debug("Feed 404 for %s (private or unavailable)", username)
            return []
        response.raise_for_status()
        try:
            payload = response.json()
        except ValueError as exc:  # pragma: no cover - network variability
            snippet = response.text[:200].strip()
            raise ValueError(
                f"Instagram feed returned non-JSON response for {username}: {snippet}"
            ) from exc
        items = payload.get("items") or []
        posts: List[Post] = []
        for item in items[: self.max_posts]:
            parsed = self._parse_feed_item(item)
            if parsed:
                posts.append(parsed)
        return posts

    def _parse_feed_item(self, item: Dict) -> Optional[Post]:
        post_id = item.get("id") or item.get("pk")
        if not post_id:
            return None
        code = item.get("code", "")
        caption_obj = item.get("caption") or {}
        caption = caption_obj.get("text") or ""
        taken_at = item.get("taken_at")
        taken_at_iso = (
            datetime.fromtimestamp(taken_at, tz=timezone.utc).isoformat()
            if taken_at
            else None
        )
        base_media = item
        if item.get("media_type") == 8 and item.get("carousel_media"):
            base_media = item["carousel_media"][0]
        thumbnail_url = _first_candidate_url(base_media)
        display_url = thumbnail_url or _first_candidate_url(item)
        is_video = bool(base_media.get("video_versions"))
        video_views = (
            base_media.get("play_count")
            or base_media.get("view_count")
            or item.get("play_count")
            or item.get("view_count")
        )
        like_count = item.get("like_count")
        comment_count = item.get("comment_count")
        return Post(
            post_id=str(post_id),
            shortcode=code,
            caption=caption,
            thumbnail_url=thumbnail_url,
            display_url=display_url,
            permalink=f"https://www.instagram.com/p/{code}/" if code else "",
            is_video=is_video,
            video_view_count=video_views,
            like_count=like_count,
            comment_count=comment_count,
            taken_at=taken_at_iso,
            media_type=item.get("product_type")
            or str(item.get("media_type")),
        )


def _safe_get(obj: Dict, key: str, inner_key: str) -> Optional[int]:
    value = obj.get(key)
    if isinstance(value, dict):
        inner = value.get(inner_key)
        if isinstance(inner, int):
            return inner
    return None


def _first_candidate_url(media: Dict) -> str:
    versions = media.get("image_versions2", {})
    candidates = versions.get("candidates", []) if isinstance(versions, dict) else []
    for candidate in candidates:
        url = candidate.get("url")
        if url:
            return url
    return media.get("thumbnail_url") or media.get("display_url", "")


def normalize_username(raw: str) -> str:
    return str(raw).strip().lstrip("@")


def load_usernames_from_excel(excel_path: str, column: str) -> List[str]:
    df = pd.read_excel(excel_path)
    if column not in df.columns:
        raise ValueError(
            f"Column '{column}' not found in Excel file. Available: {list(df.columns)}"
        )
    usernames = []
    for raw in df[column].dropna():
        username = normalize_username(raw)
        if username:
            usernames.append(username)
    if not usernames:
        raise ValueError("No usernames found in the Excel sheet")
    return usernames


def load_usernames_from_txt(path: str) -> List[str]:
    usernames: List[str] = []
    with open(path, "r", encoding="utf-8") as handle:
        for line in handle:
            for item in line.replace(",", " ").split():
                username = normalize_username(item)
                if username:
                    usernames.append(username)
    if not usernames:
        raise ValueError("No usernames found in the text file")
    return usernames


def collect_usernames(args: argparse.Namespace) -> List[str]:
    """Load usernames from the data sources specified in CLI args."""
    combined: List[str] = []
    if args.excel:
        combined.extend(load_usernames_from_excel(args.excel, args.column))
    if args.txt:
        combined.extend(load_usernames_from_txt(args.txt))
    if not combined:
        raise ValueError("Pass --excel and/or --txt to provide usernames")
    seen = set()
    deduped: List[str] = []
    for username in combined:
        if username not in seen:
            seen.add(username)
            deduped.append(username)
    return deduped


def parse_args(argv: Optional[List[str]] = None) -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Scrape Instagram profile info and posts into SQLite",
    )
    parser.add_argument(
        "--excel",
        help="Path to Excel file containing Instagram usernames",
    )
    parser.add_argument(
        "--column",
        default="username",
        help="Column name inside the Excel sheet that stores usernames",
    )
    parser.add_argument(
        "--txt",
        help="Optional text file with usernames (one per line, commas or spaces allowed)",
    )
    parser.add_argument(
        "--db",
        default="instagram.db",
        help="Path to the SQLite DB that will store the scraped data",
    )
    parser.add_argument(
        "--cookie",
        help="Instagram sessionid cookie. Falls back to IG_SESSIONID env variable",
    )
    parser.add_argument(
        "--delay",
        type=float,
        default=2.0,
        help="Delay between requests to reduce rate-limiting (seconds)",
    )
    parser.add_argument(
        "--max-posts",
        type=int,
        default=18,
        help="Maximum number of recent posts to store per account",
    )
    parser.add_argument(
        "--timeout",
        type=int,
        default=30,
        help="HTTP timeout per request in seconds",
    )
    parser.add_argument(
        "--user-agent",
        default=DEFAULT_USER_AGENT,
        help="Override the User-Agent header sent to Instagram",
    )
    parser.add_argument(
        "--log-level",
        default="INFO",
        choices=["DEBUG", "INFO", "WARNING", "ERROR"],
        help="Logging verbosity",
    )
    return parser.parse_args(argv)


def configure_logging(level: str) -> None:
    logging.basicConfig(
        level=getattr(logging, level),
        format="%(asctime)s [%(levelname)s] %(message)s",
    )


def main(argv: Optional[List[str]] = None) -> int:
    args = parse_args(argv)
    configure_logging(args.log_level)
    cookie = args.cookie or os.getenv("IG_SESSIONID")
    if not cookie:
        logging.error("Instagram session cookie missing. Pass --cookie or set IG_SESSIONID")
        return 1

    try:
        usernames = collect_usernames(args)
    except Exception as exc:  # pragma: no cover - CLI guardrail
        logging.error("Failed to load usernames: %s", exc)
        return 1

    scraper = InstagramScraper(
        cookie,
        user_agent=args.user_agent,
        timeout=args.timeout,
        max_posts=args.max_posts,
    )
    db = Database(args.db)
    failures = []
    for username in usernames:
        logging.info("Scraping %s", username)
        try:
            profile, posts = scraper.fetch_user(username)
            db.upsert_profile(profile)
            db.upsert_posts(username, posts)
            logging.info("Stored %s with %d posts", username, len(posts))
        except Exception as exc:  # noqa: BLE001
            logging.error("Failed to process %s: %s", username, exc)
            failures.append((username, str(exc)))
        time.sleep(max(args.delay, 0))

    db.close()
    if failures:
        logging.warning("%d usernames failed", len(failures))
        for username, reason in failures:
            logging.warning("- %s: %s", username, reason)
        return 2
    logging.info("All done")
    return 0


if __name__ == "__main__":
    sys.exit(main())
