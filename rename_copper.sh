
#!/usr/bin/env bash

set -euo pipefail

ROOT="$(cd "$(dirname "$0")" && pwd)"
BACKUP="$ROOT/.copper-cubic-backup-all"
SCRIPT="$ROOT/$(basename "$0")"

cd "$ROOT"

replace_name() {
    local name="$1"
    name="${name//Copper/Cubic}"
    name="${name//copper/cubic}"
    name="${name//COPPER/CUBIC}"
    printf '%s' "$name"
}

case "${1:-}" in
    apply)
        if [[ -d "$BACKUP" ]]; then
            echo "[!] Backup already exists."
            echo "[!] Revert first before applying again."
            exit 1
        fi

        mkdir -p "$BACKUP/files"
        touch "$BACKUP/renames.tsv"

        changed=0
        renamed=0

        echo "=== Replacing file contents ==="

        while IFS= read -r -d '' file; do
            [[ "$file" == "$SCRIPT" ]] && continue

            # Skip binary files
            grep -Iq '' "$file" 2>/dev/null || continue

            # Only process files containing target names
            grep -qE 'Copper|copper|COPPER' "$file" || continue

            rel="${file#"$ROOT"/}"
            mkdir -p "$BACKUP/files/$(dirname "$rel")"

            cp -p "$file" "$BACKUP/files/$rel"

            temp="${file}.cubic-tmp"

            sed \
                -e 's/Copper/Cubic/g' \
                -e 's/copper/cubic/g' \
                -e 's/COPPER/CUBIC/g' \
                "$file" > "$temp"

            cat "$temp" > "$file"
            rm "$temp"

            echo "[CONTENT] $rel"
            ((changed+=1))

        done < <(
            find "$ROOT" \
                -type d \( \
                    -name .git -o \
                    -name .gradle -o \
                    -name build -o \
                    -name .copper-cubic-backup-all \
                \) -prune \
                -o -type f -print0
        )

        echo ""
        echo "=== Renaming filenames ==="

        while IFS= read -r -d '' file; do
            [[ "$file" == "$SCRIPT" ]] && continue

            dir="$(dirname "$file")"
            base="$(basename "$file")"
            newbase="$(replace_name "$base")"

            [[ "$base" == "$newbase" ]] && continue

            newfile="$dir/$newbase"

            if [[ -e "$newfile" ]]; then
                echo "[ERROR] Rename collision: $newfile"
                exit 1
            fi

            oldrel="${file#"$ROOT"/}"
            newrel="${newfile#"$ROOT"/}"

            mv "$file" "$newfile"

            printf '%s\t%s\n' "$oldrel" "$newrel" \
                >> "$BACKUP/renames.tsv"

            echo "[RENAMED] $oldrel -> $newrel"
            ((renamed+=1))

        done < <(
            find "$ROOT" \
                -type d \( \
                    -name .git -o \
                    -name .gradle -o \
                    -name build -o \
                    -name .copper-cubic-backup-all \
                \) -prune \
                -o -type f -print0
        )

        echo ""
        echo "=== COMPLETE ==="
        echo "Modified files: $changed"
        echo "Renamed files: $renamed"
        echo "Backup: $BACKUP"
        ;;

    revert)
        if [[ ! -d "$BACKUP" ]]; then
            echo "[!] No backup found."
            exit 1
        fi

        echo "=== Reverting filenames ==="

        while IFS=$'\t' read -r oldrel newrel; do
            [[ -z "$oldrel" ]] && continue

            oldfile="$ROOT/$oldrel"
            newfile="$ROOT/$newrel"

            if [[ -e "$newfile" ]]; then
                if [[ -e "$oldfile" ]]; then
                    echo "[ERROR] Original filename already exists: $oldrel"
                    exit 1
                fi

                mkdir -p "$(dirname "$oldfile")"
                mv "$newfile" "$oldfile"
                echo "[RESTORED NAME] $oldrel"
            fi

        done < "$BACKUP/renames.tsv"

        echo ""
        echo "=== Restoring file contents ==="

        while IFS= read -r -d '' backup; do
            rel="${backup#"$BACKUP/files"/}"
            original="$ROOT/$rel"

            mkdir -p "$(dirname "$original")"
            cp -p "$backup" "$original"

            echo "[RESTORED CONTENT] $rel"

        done < <(find "$BACKUP/files" -type f -print0)

        rm -rf "$BACKUP"

        echo ""
        echo "[DONE] Original files and names restored."
        ;;

    *)
        echo "Copper -> Cubic Repository Renamer"
        echo ""
        echo "Usage:"
        echo "  bash rename_copper.sh apply"
        echo "  bash rename_copper.sh revert"
        exit 1
        ;;
esac
