"""
Safeguard: sitemap_generator.py disabled to prevent runaway subprocess exhaustion.
Sitemaps are statically served and updated via lightweight database export when needed.
"""
import sys

def main():
    print("[SitemapGenerator] Scraping loop is permanently disabled to protect server CPU and memory.")
    sys.exit(0)

if __name__ == "__main__":
    main()
