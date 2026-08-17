#!/usr/bin/env bash

set -euo pipefail

THEMES_DIR="${1:-$(cd "$(dirname "${BASH_SOURCE[0]}")/../themes" && pwd)}"

fail() { echo "FAIL: $1"; exit 1; }
ok()   { printf '  \033[0;32m✓\033[0m %s\n' "$1"; }

REFERENCE_THAT_LEAVES_OUR_OWN_ORIGIN="https?://|url\([\"']?//|@import[[:space:]]*[\"']//"

strip_xml_namespace_identifiers_which_are_never_fetched() {
  sed -E "s/xmlns(:[A-Za-z0-9]+)?=\"https?:\/\/[^\"]*\"//g; s/xmlns(:[A-Za-z0-9]+)?='https?:\/\/[^']*'//g"
}

list_files_a_browser_parses_when_it_renders_the_login_page() {
  find "$1" -type f \( -name '*.css' -o -name '*.ftl' -o -name '*.html' -o -name '*.htm' \
    -o -name '*.js' -o -name '*.mjs' -o -name '*.json' -o -name '*.svg' -o -name '*.properties' \)
}

find_references_that_leave_our_own_origin() {
  local dir="$1" parsed_file
  while IFS= read -r parsed_file; do
    grep -n -E "$REFERENCE_THAT_LEAVES_OUR_OWN_ORIGIN" "$parsed_file" 2>/dev/null \
      | strip_xml_namespace_identifiers_which_are_never_fetched \
      | grep -E "$REFERENCE_THAT_LEAVES_OUR_OWN_ORIGIN" \
      | sed "s|^|${parsed_file}:|" \
      || true
  done < <(list_files_a_browser_parses_when_it_renders_the_login_page "$dir")
}

find_relative_urls_that_resolve_to_no_shipped_file() {
  local dir="$1" css_file relative_target joined_target
  while IFS= read -r css_file; do
    while IFS= read -r relative_target; do
      [[ -z "$relative_target" ]] && continue
      joined_target="$(dirname "$css_file")/$relative_target"
      [[ -f "$joined_target" ]] \
        || echo "${css_file}: url(${relative_target}) resolves to ${joined_target}, which does not exist"
    done < <(grep -o -E "url\([\"']?[^\"')]+[\"']?\)" "$css_file" \
               | sed -E "s/^url\([\"']?//; s/[\"']?\)$//" \
               | grep -v -E "^(data:|https?:|//|#|/)" \
               || true)
  done < <(find "$dir" -type f -name '*.css')
}

assert_theme_dir_is_clean() {
  local dir="$1" escaping_references missing_targets

  escaping_references="$(find_references_that_leave_our_own_origin "$dir")"
  if [[ -n "$escaping_references" ]]; then
    echo "$escaping_references"
    fail "a Keycloak theme resource is fetched from the public internet; every login render then depends on a third-party host"
  fi
  ok "no theme file that the browser parses references the public internet"

  missing_targets="$(find_relative_urls_that_resolve_to_no_shipped_file "$dir")"
  if [[ -n "$missing_targets" ]]; then
    echo "$missing_targets"
    fail "a relative theme URL points at a file the image does not ship; the browser falls back silently and the page looks correct while it is broken"
  fi
  ok "every relative theme URL resolves to a shipped file"
}

run_selftest_so_a_broken_scanner_cannot_report_green() {
  local planted_dir planted_findings
  planted_dir="$(mktemp -d)"
  trap 'rm -rf "$planted_dir"' RETURN

  mkdir -p "$planted_dir/login/resources/css" "$planted_dir/login/resources/img"
  printf "%s\n" "@import url('https://fonts.googleapis.com/css2?family=Roboto');" \
    > "$planted_dir/login/resources/css/planted-webfont-import.css"
  printf "%s\n" '<svg xmlns="http://www.w3.org/2000/svg"></svg>' \
    > "$planted_dir/login/resources/img/planted-inline-glyph.svg"

  planted_findings="$(find_references_that_leave_our_own_origin "$planted_dir")"
  case "$planted_findings" in
    *planted-webfont-import.css*) ok "selftest: the scanner rejects a planted webfont import" ;;
    *) fail "selftest: the scanner missed a planted webfont import — it would report green over a real one" ;;
  esac
  case "$planted_findings" in
    *planted-inline-glyph.svg*) fail "selftest: the scanner flagged an SVG namespace identifier, which no browser fetches" ;;
    *) ok "selftest: the scanner accepts an SVG namespace identifier" ;;
  esac

  printf "%s\n" "body { background-image: url('../img/absent-splash.jpg'); }" \
    > "$planted_dir/login/resources/css/planted-missing-target.css"
  case "$(find_relative_urls_that_resolve_to_no_shipped_file "$planted_dir")" in
    *absent-splash.jpg*) ok "selftest: the scanner rejects a relative URL with no shipped file" ;;
    *) fail "selftest: the scanner missed a relative URL with no shipped file" ;;
  esac

  rm "$planted_dir/login/resources/css/planted-missing-target.css"
  printf "%s\n" "body { background-image: url('../img/planted-inline-glyph.svg'); }" \
    > "$planted_dir/login/resources/css/planted-present-target.css"
  if [[ -z "$(find_relative_urls_that_resolve_to_no_shipped_file "$planted_dir")" ]]; then
    ok "selftest: the scanner accepts a relative URL whose file is shipped"
  else
    fail "selftest: the scanner flagged a relative URL whose file is shipped"
  fi
}

if [[ "${1:-}" == "--selftest" ]]; then
  run_selftest_so_a_broken_scanner_cannot_report_green
  echo "PASS"
  exit 0
fi

echo "scanning ${THEMES_DIR}"
assert_theme_dir_is_clean "$THEMES_DIR"
echo "PASS"
