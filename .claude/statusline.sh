#!/usr/bin/env bash
# Claude Code status line — colored segments
# Segments: model | git branch | ctx:N%/Nk | 5h:N%

input=$(cat)

model=$(echo "$input"    | jq -r '.model.display_name // empty')
dir=$(echo "$input"      | jq -r '.cwd // .workspace.current_dir // empty')
pct=$(echo "$input"      | jq -r '.context_window.used_percentage // empty')
ctx_size=$(echo "$input" | jq -r '.context_window.context_window_size // empty')
rate_5h=$(echo "$input"  | jq -r '.rate_limits.five_hour.used_percentage // empty')

branch=$(GIT_OPTIONAL_LOCKS=0 git -C "${dir:-.}" symbolic-ref --short HEAD 2>/dev/null)

# ANSI helpers (using $'…' so ESC is a real byte, not a literal string)
RESET=$'\e[0m'
CYAN=$'\e[36m'
GREEN=$'\e[32m'
YELLOW=$'\e[33m'
RED=$'\e[31m'
DIM=$'\e[2m'

SEP="${DIM} | ${RESET}"

parts=()

# Model segment — cyan with sparkle
if [ -n "$model" ]; then
    parts+=("${CYAN}✨ ${model}${RESET}")
fi

# Git branch segment — green with leaf emoji
if [ -n "$branch" ]; then
    parts+=("${GREEN}⎇ ${branch}${RESET}")
fi

# Context % segment — ctx:N%/Nk, color shifts by usage threshold
if [ -n "$pct" ]; then
    pct_int=$(printf '%.0f' "$pct")
    if   [ "$pct_int" -ge 80 ]; then CTX_COLOR=$RED
    elif [ "$pct_int" -ge 50 ]; then CTX_COLOR=$YELLOW
    else                              CTX_COLOR=$GREEN
    fi
    if [ -n "$ctx_size" ]; then
        ksize=$((ctx_size / 1000))
        parts+=("${CTX_COLOR}📊 ctx:${pct_int}%/${ksize}k${RESET}")
    else
        parts+=("${CTX_COLOR}📊 ctx:${pct_int}%${RESET}")
    fi
fi

# 5-hour rate-limit segment — ⏱ 5h:N%, color shifts by usage threshold
if [ -n "$rate_5h" ]; then
    rate_int=$(printf '%.0f' "$rate_5h")
    if   [ "$rate_int" -ge 80 ]; then RATE_COLOR=$RED
    elif [ "$rate_int" -ge 50 ]; then RATE_COLOR=$YELLOW
    else                               RATE_COLOR=$GREEN
    fi
    parts+=("${RATE_COLOR}⏱ 5h:${rate_int}%${RESET}")
fi

# Join with dim separator
out=""
for part in "${parts[@]}"; do
    if [ -z "$out" ]; then
        out="$part"
    else
        out="${out}${SEP}${part}"
    fi
done

printf '%s\n' "$out"