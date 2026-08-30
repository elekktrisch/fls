#!/usr/bin/env bash
set -euo pipefail

readonly J0_LOCATIONS_BASELINE_SPEC='e2e/tests/real-idp/locations-crud-tenant-isolation.spec.ts'
readonly CLEAN_SEED_RUNNABLE_SPEC_DIRECTORY='e2e/tests/real-idp/'
readonly WEB_WORKING_DIRECTORY='alpenflight/web'
readonly PARITY_TEST_TOKEN_SEPARATORS='[:space:],;()+'
readonly MOCK_TEST_TOKEN_SEPARATORS='[:space:],;'
readonly HOW_THE_DERIVE_READS_PARITY_TEST='The derive reads the parity_test: line plus its indented continuation lines, drops a # comment, splits on whitespace , ; ( ) and +, and keeps only a token that ends in .spec.ts. A directory, a brace expansion or a regex declares no spec to this lane.'
readonly BARE_PLUS_JOINS_TOKENS_AND_IS_NOT_A_FILTER='+'
readonly HOW_THE_DERIVE_READS_MOCK_TEST='The derive reads the mock_test: line plus its indented continuation lines, drops a # comment, splits on whitespace , and ; only so a Playwright alternation regex stays one token, and drops a bare + that joins two tokens.'

lane_kind="${1:-}"
branch_under_work="${2:-}"

write_step_output() {
  if [ -n "${GITHUB_OUTPUT:-}" ]; then
    printf '%s=%s\n' "$1" "$2" >> "$GITHUB_OUTPUT"
  else
    printf '%s=%s\n' "$1" "$2"
  fi
}

journey_id_of_branch() {
  printf '%s' "$1" | sed -nE 's#^integration/(J-[0-9]+[a-z]?)$#\1#p'
}

journey_file_for() {
  local journey_id="$1"
  grep -lE "^id:[[:space:]]*${journey_id}([[:space:]]|$)" \
    docs/modernization/stories/${journey_id}-*.md \
    docs/modernization/stories/implemented/${journey_id}-*.md \
    2>/dev/null | head -1 || true
}

frontmatter_value_of() {
  local key="$1" journey_file="$2"
  awk -v prefix="${key}:" '
    index($0, prefix) == 1 && !seen { seen = 1; print substr($0, length(prefix) + 1); next }
    seen && /^[[:space:]]/ { print; next }
    seen { exit }
  ' "$journey_file" | sed -E 's/(^|[[:space:]])#.*$//'
}

declared_parity_spec_tokens_of() {
  frontmatter_value_of parity_test "$1" \
    | tr "$PARITY_TEST_TOKEN_SEPARATORS" '\n' \
    | sed -E 's#^alpenflight/web/##' \
    | grep -E '\.spec\.ts$' \
    | awk '!already_emitted[$0]++' || true
}

spec_is_runnable_by_the_clean_seed_proof_job() {
  local spec="$1"
  case "$spec" in
    "${CLEAN_SEED_RUNNABLE_SPEC_DIRECTORY}"*) ;;
    *) return 1 ;;
  esac
  [ -f "${WEB_WORKING_DIRECTORY}/${spec}" ] || return 1
  grep -qE '^[[:space:]]*test\(' "${WEB_WORKING_DIRECTORY}/${spec}"
}

reason_the_clean_seed_proof_job_rejects() {
  local spec="$1"
  case "$spec" in
    "${CLEAN_SEED_RUNNABLE_SPEC_DIRECTORY}"*) ;;
    *)
      printf 'it is not under %s, the only directory whose specs self-seed against the clean-seed database' \
        "$CLEAN_SEED_RUNNABLE_SPEC_DIRECTORY"
      return 0
      ;;
  esac
  if [ ! -f "${WEB_WORKING_DIRECTORY}/${spec}" ]; then
    printf 'no file exists at %s/%s' "$WEB_WORKING_DIRECTORY" "$spec"
    return 0
  fi
  printf 'it declares no active top-level test( — every case is test.skip or test.fixme, so the run would capture nothing'
}

emit_proof_lane() {
  write_step_output spec "$1"
  write_step_output journey "$2"
  write_step_output is_baseline "$3"
  echo "── proof spec: $1  (journey=$2, baseline=$3) ──"
}

derive_proof_lane() {
  local journey_id journey_file spec rejected_token_count=0
  local runnable_specs=()
  journey_id="$(journey_id_of_branch "$branch_under_work")"
  if [ -z "$journey_id" ]; then
    echo "branch '${branch_under_work}' is not integration/J-NNN, so no journey is under work → J-0 baseline"
    emit_proof_lane "$J0_LOCATIONS_BASELINE_SPEC" 'J-0' 'true'
    return 0
  fi
  journey_file="$(journey_file_for "$journey_id")"
  if [ -z "$journey_file" ] || [ ! -f "$journey_file" ]; then
    echo "::error title=No journey file for ${journey_id}::Branch '${branch_under_work}' is an integration branch, but no file declares 'id: ${journey_id}' in docs/modernization/stories/ or docs/modernization/stories/implemented/. Carve the journey file with /do-plan, or repair its frontmatter id. CI does not substitute the J-0 baseline and report that green."
    return 1
  fi
  echo "${journey_id} journey file: ${journey_file}"
  while IFS= read -r spec; do
    [ -n "$spec" ] || continue
    if spec_is_runnable_by_the_clean_seed_proof_job "$spec"; then
      runnable_specs+=("$spec")
    else
      rejected_token_count=$((rejected_token_count + 1))
      echo "::warning title=Declared proof spec dropped::${journey_id} declares '${spec}' in parity_test:, and the per-push proof job does not run it: $(reason_the_clean_seed_proof_job_rejects "$spec"). ${HOW_THE_DERIVE_READS_PARITY_TEST}"
    fi
  done < <(declared_parity_spec_tokens_of "$journey_file")
  if [ "${#runnable_specs[@]}" -eq 0 ]; then
    echo "${journey_id} declares no proof spec this job can run (${rejected_token_count} token(s) rejected above) → J-0 baseline. ${HOW_THE_DERIVE_READS_PARITY_TEST}"
    emit_proof_lane "$J0_LOCATIONS_BASELINE_SPEC" "$journey_id" 'true'
    return 0
  fi
  emit_proof_lane "${runnable_specs[*]}" "$journey_id" 'false'
}

declared_mock_filter_tokens_of() {
  frontmatter_value_of mock_test "$1" \
    | tr "$MOCK_TEST_TOKEN_SEPARATORS" '\n' \
    | sed -E 's#^alpenflight/web/##' \
    | grep -vE '^$' \
    | grep -vxF "$BARE_PLUS_JOINS_TOKENS_AND_IS_NOT_A_FILTER" \
    | awk '!already_emitted[$0]++' || true
}

specs_selected_by_path_stem() {
  local stem="$1"
  if [ -d "${WEB_WORKING_DIRECTORY}/${stem}" ]; then
    find "${WEB_WORKING_DIRECTORY}/${stem}" -type f -name '*.spec.ts'
  elif [ -f "${WEB_WORKING_DIRECTORY}/${stem}" ]; then
    printf '%s\n' "${WEB_WORKING_DIRECTORY}/${stem}"
  else
    ls "${WEB_WORKING_DIRECTORY}/${stem}"*.spec.ts 2>/dev/null || true
  fi
}

specs_selected_by_filter_token() {
  local token="$1" alternation_prefix alternation_body alternative selected
  case "$token" in
    *'('* | *'|'*)
      alternation_prefix="$(printf '%s' "$token" | sed -E 's/\(.*$//')"
      alternation_body="$(printf '%s' "$token" | sed -E 's/^[^(]*\(//; s/\)[^)]*$//')"
      while IFS= read -r alternative; do
        [ -n "$alternative" ] || continue
        selected="$(specs_selected_by_path_stem "${alternation_prefix}${alternative}")"
        [ -n "$selected" ] || return 1
        printf '%s\n' "$selected"
      done < <(printf '%s\n' "$alternation_body" | tr '|' '\n')
      ;;
    *)
      selected="$(specs_selected_by_path_stem "$token")"
      [ -n "$selected" ] || return 1
      printf '%s\n' "$selected"
      ;;
  esac
}

any_spec_declares_an_active_test() {
  local spec_file
  while IFS= read -r spec_file; do
    [ -n "$spec_file" ] || continue
    if grep -qE '^[[:space:]]*test\(' "$spec_file"; then
      return 0
    fi
  done <<< "$1"
  return 1
}

emit_mock_lane() {
  write_step_output filter "$1"
  write_step_output is_full "$2"
  echo "── mock-e2e filter: '${1:-<full chromium suite>}'  (is_full=$2) ──"
}

derive_mock_lane() {
  local journey_id journey_file token selected_by_token all_selected_specs=''
  local filter_tokens=()
  journey_id="$(journey_id_of_branch "$branch_under_work")"
  if [ -z "$journey_id" ]; then
    echo "branch '${branch_under_work}' is not integration/J-NNN → full chromium suite"
    emit_mock_lane '' 'true'
    return 0
  fi
  journey_file="$(journey_file_for "$journey_id")"
  if [ -z "$journey_file" ] || [ ! -f "$journey_file" ]; then
    echo "no journey file for ${journey_id} → full chromium suite"
    emit_mock_lane '' 'true'
    return 0
  fi
  while IFS= read -r token; do
    [ -n "$token" ] || continue
    filter_tokens+=("$token")
  done < <(declared_mock_filter_tokens_of "$journey_file")
  if [ "${#filter_tokens[@]}" -eq 0 ]; then
    echo "${journey_id} declares no mock_test: token → full chromium suite. ${HOW_THE_DERIVE_READS_MOCK_TEST}"
    emit_mock_lane '' 'true'
    return 0
  fi
  for token in "${filter_tokens[@]}"; do
    if ! selected_by_token="$(specs_selected_by_filter_token "$token")"; then
      echo "${journey_id} mock_test: token '${token}' selects no spec on disk → full chromium suite. ${HOW_THE_DERIVE_READS_MOCK_TEST}"
      emit_mock_lane '' 'true'
      return 0
    fi
    all_selected_specs="${all_selected_specs}
${selected_by_token}"
  done
  if ! any_spec_declares_an_active_test "$all_selected_specs"; then
    echo "${journey_id} mock_test: '${filter_tokens[*]}' selects no active (non-fixme) test → full chromium suite until thickened"
    emit_mock_lane '' 'true'
    return 0
  fi
  emit_mock_lane "${filter_tokens[*]}" 'false'
}

echo "branch under work: '${branch_under_work}'"
case "$lane_kind" in
  proof) derive_proof_lane ;;
  mock) derive_mock_lane ;;
  *)
    echo "::error title=Unknown lane::derive-journey-lane.sh needs 'proof' or 'mock' as its first argument, and the branch name as its second. It received '${lane_kind}'."
    exit 1
    ;;
esac
