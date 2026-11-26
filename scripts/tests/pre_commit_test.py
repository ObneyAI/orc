"""Exercise the installed hook through real commits in disposable repositories."""

import os
import re
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest


ROOT = Path(__file__).resolve().parents[2]
SERVICE = "components/orc-service/src/ai/obney/orc/orc_service/"
SOURCE = SERVICE + "core/read_models.clj"
SCHEMAS = SERVICE + "interface/schemas.clj"


class PreCommitTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix="orc-hook-test-")
        self.addCleanup(self.temp.cleanup)
        self.repo = Path(self.temp.name) / "repo with spaces"
        self.repo.mkdir()
        self.snapshots = Path(self.temp.name) / "snapshots"
        self.snapshots.mkdir()
        self.env = dict(os.environ, TMPDIR=str(self.snapshots))
        # Do not inherit a caller's alternate index or repository routing.
        for key in ("GIT_DIR", "GIT_WORK_TREE", "GIT_INDEX_FILE", "GIT_COMMON_DIR"):
            self.env.pop(key, None)
        for name in ("components", "bases", "projects", "development", "scripts", "localstack-init", ".githooks"):
            (self.repo / name).mkdir()
        for name in (
            ".githooks/pre-commit",
            "scripts/install-hooks.sh",
            "scripts/lint.sh",
            "scripts/lint_base_changes.bb",
            "scripts/lint_code_comments.bb",
            "scripts/lint_explicit_dependencies.bb",
            "scripts/lint_no_declare.bb",
            "scripts/lint_no_sql_delete.bb",
            "scripts/lint_service_components.bb",
            "scripts/lint_grain_declarations.bb",
            "scripts/lint_logic_files.bb",
            "scripts/lint_view_files.bb",
            "scripts/lint_grain_logic.bb",
            "scripts/lint_read_model_versions.bb",
            "scripts/lint_no_any_schemas.bb",
        ):
            shutil.copy2(ROOT / name, self.repo / name)
        self.run_command("git", "init", "--quiet")
        self.run_command("git", "config", "user.name", "Hook Test")
        self.run_command("git", "config", "user.email", "hook-test@example.invalid")
        self.run_command("git", "config", "commit.gpgsign", "false")
        self.run_command("sh", "scripts/install-hooks.sh")
        self.write(SOURCE, "(defreadmodel :sample rows {:version 1} [s e] s)")
        self.write(SCHEMAS, "(ns fixture.schemas)")
        self.run_command("git", "add", ".")

    def test_existing_violation_blocks_an_unrelated_commit(self):
        self.write(SOURCE, "(defreadmodel :sample rows {:version 2} [s e] s)")
        self.run_command("git", "add", SOURCE)
        self.run_command("git", "commit", "--no-verify", "-qm", "Fixture before hook adoption")
        self.write("README.md", "Unrelated change")
        self.run_command("git", "add", "README.md")
        self.assertIn("must use literal version 1", self.commit(succeeds=False))

    def test_unstaged_runner_cannot_supply_a_missing_staged_rule_suite(self):
        self.run_command("git", "rm", "--cached", "scripts/lint.sh")
        self.assertIn("staged index must include scripts/lint.sh", self.commit(succeeds=False))

    def test_renamed_service_keeps_its_public_namespace_and_layout_gate(self):
        root = "components/colbert-service/src/ai/obney/orc/colbert/"
        self.write(root + "interface/schemas.clj", "(ns fixture.schemas)")
        self.write(root + "core/helper.clj", "(defn misplaced [] 1)")
        self.run_command("git", "add", "components/colbert-service")
        self.assertIn("Backend function misplaced", self.commit(succeeds=False))

    def test_unsuffixed_brick_cannot_own_grain_declarations(self):
        self.write("components/example/src/ai/obney/orc/example/core/commands.clj",
                   "(defcommand :example create {})")
        self.run_command("git", "add", ".")
        self.assertIn("declarations outside service components are forbidden", self.commit(succeeds=False))

    def test_view_placement_checks_staged_content(self):
        name = SERVICE + "views/detail.clj"
        self.write(name, "(ns ai.obney.orc.orc-service.views.detail)")
        self.run_command("git", "add", name)
        (self.repo / name).unlink()
        self.assertIn("Service views must live in", self.commit(succeeds=False))
        self.run_command("git", "add", name)
        self.write(SERVICE + "views.clj", "(ns ai.obney.orc.orc-service.views) (defn page [] [:div])")
        self.run_command("git", "add", ".")
        self.commit()

    def test_requiring_resolve_blocks_commit_including_tests(self):
        self.write("components/orc-service/test/fixture_test.clj",
                   "(def lookup clojure.core/requiring-resolve)")
        self.run_command("git", "add", ".")
        self.assertIn("requiring-resolve is banned", self.commit(succeeds=False))

    def test_grain_boundary_checks_staged_logic_and_allows_orchestration(self):
        logic = SERVICE + "core/logic.clj"
        self.write(logic, "(ns fixture.logic (:require [ai.obney.grain.read-model-processor-v2.interface :as r])) (defn lookup [ctx] (r/value ctx :fixture/rows))")
        self.run_command("git", "add", logic)
        self.write(logic, "(ns fixture.logic) (defn summary [rows] (count rows))")
        self.assertIn("Grain runtime reference", self.commit(succeeds=False))
        self.run_command("git", "add", logic)
        self.write(SOURCE, "(ns fixture.reads (:require [ai.obney.grain.read-model-processor-v2.interface :as r])) (defn lookup [ctx] (r/value ctx :fixture/rows))")
        self.run_command("git", "add", SOURCE)
        self.commit()

    def test_declare_ban_checks_staged_development_and_test_sources(self):
        for name, source in (
            ("development/src/probe.clj", "(declare forward)"),
            ("components/orc-service/test/fixture_test.clj",
             "(ns fixture (:require [clojure.core :as c])) (c/declare forward)"),
        ):
            with self.subTest(name=name):
                self.write(name, source)
                self.run_command("git", "add", name)
                self.write(name, "(def forward :unstaged-fix)")
                self.assertIn("declare is banned", self.commit(succeeds=False))
                self.run_command("git", "add", name)
        self.commit()

    def test_tooling_lookup_is_checked_from_staged_content(self):
        name = "localstack-init/probe.clj"
        self.write(name, "(requiring-resolve 'example/run)")
        self.run_command("git", "add", name)
        self.write(name, "(println \"unstaged fix\")")
        self.assertIn("requiring-resolve is banned", self.commit(succeeds=False))

    def test_sql_delete_ban_checks_staged_cleanup_and_sql_files(self):
        sql = "DELETE" + " FROM derived_cache WHERE id = ?"
        for name, source in (
            ("development/src/probe.clj", f'(jdbc/execute! connection ["{sql}"])'),
            ("scripts/cleanup.sql", sql),
        ):
            with self.subTest(name=name):
                self.write(name, source)
                self.run_command("git", "add", name)
                self.write(name, "")
                self.assertIn("SQL DELETE statements are banned", self.commit(succeeds=False))
                self.run_command("git", "add", name)
        self.commit()

    def test_comment_policy_checks_staged_sources_and_allows_dev_discards(self):
        name = "localstack-init/probe.clj"
        self.write(name, "; forbidden explanation\n(def x 1)")
        self.run_command("git", "add", name)
        self.write(name, "(def x 1)")
        self.assertIn("Code comments are banned", self.commit(succeeds=False))
        self.run_command("git", "add", name)
        self.write("development/src/probe.clj", "#_(println :example)\n(defn f \"Public docstring\" [] :ok)")
        self.run_command("git", "add", ".")
        self.commit()

    def test_section_header_survives_a_shortened_source_file(self):
        self.write(SOURCE, ";; --- Query helpers ---\n" + "\n".join(
            f"(def value-{index} {index})" for index in range(200)))
        self.run_command("git", "add", SOURCE)
        self.commit()
        self.write(SOURCE, ";; --- Query helpers ---\n(def value 1)")
        self.run_command("git", "add", SOURCE)
        self.commit()
        self.write(SOURCE, "(def fields\n  {\n   ;; --- Optional fields ---\n   :name :string})")
        self.run_command("git", "add", SOURCE)
        self.commit()

    def run_command(self, *args, check=True, cwd=None, env=None):
        result = subprocess.run(
            args, cwd=cwd or self.repo, env=env or self.env,
            text=True, capture_output=True,
        )
        if check and result.returncode:
            self.fail(f"{args}: {result.stdout}\n{result.stderr}")
        return result

    def write(self, name, content):
        path = self.repo / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(content)

    def commit(self, succeeds=True, env=None):
        parent = self.run_command("git", "rev-parse", "--verify", "HEAD", check=False).stdout
        before = self.run_command("git", "diff", "--cached", "--binary").stdout
        working = self.run_command("git", "diff", "--binary").stdout
        result = self.run_command(
            "git", "commit", "--quiet", "-m", "Exercise lint gate", check=False, env=env,
        )
        self.assertEqual(result.returncode == 0, succeeds, result.stdout + result.stderr)
        self.assertEqual(list(self.snapshots.iterdir()), [], "Snapshot was not cleaned up")
        self.assertEqual(self.run_command("git", "diff", "--binary").stdout, working)
        if not succeeds:
            self.assertEqual(self.run_command("git", "diff", "--cached", "--binary").stdout, before)
            self.assertEqual(self.run_command("git", "rev-parse", "--verify", "HEAD", check=False).stdout, parent)
        return result.stdout + result.stderr

    def base_review(self, *args, check=True):
        return self.run_command("bb", "scripts/lint_base_changes.bb", *args, check=check)

    def approve_base(self):
        result = self.base_review(check=False)
        self.assertEqual(result.returncode, 1, result.stdout + result.stderr)
        match = re.search(r"Review fingerprint: ([0-9a-f]{64})", result.stdout)
        self.assertIsNotNone(match, result.stdout)
        fingerprint = match.group(1)
        self.base_review("approve", fingerprint, "Fixture Human", "Reviewed infrastructure fixture")
        return fingerprint

    def test_base_gate_blocks_initial_commit_and_allows_exact_approval(self):
        self.write("bases/orc-dev/src/core.clj", "(ns fixture.base)")
        self.run_command("git", "add", "bases")
        blocked = self.commit(succeeds=False)
        self.assertIn("Review the base change", blocked)
        self.assertIn("README.md#service-ownership-and-base-review", blocked)
        self.assertIn("bases/orc-dev/src/core.clj", blocked)
        self.approve_base()
        self.assertIn("recorded human approval", self.commit())
        self.assertEqual(self.run_command("git", "status", "--porcelain").stdout, "")

    def test_approval_is_scoped_to_staged_base_content_and_parent(self):
        name = "bases/orc-dev/resources/example.bin"
        self.write(name, "first")
        self.run_command("git", "add", name)
        old = self.approve_base()
        self.write(name, "second")
        self.run_command("git", "add", name)
        stale = self.base_review("approve", old, "Fixture Human", "Old proposal", check=False)
        self.assertNotEqual(stale.returncode, 0)
        self.assertIn("proposal changed", stale.stdout)
        self.commit(succeeds=False)
        self.approve_base()
        self.write(name, "unstaged third version")
        self.write("components/orc-service/deps.edn", '{:paths ["src"]}')
        self.run_command("git", "add", "components/orc-service/deps.edn")
        self.commit()
        self.run_command("git", "add", name)
        self.commit(succeeds=False)
        self.approve_base()
        self.commit()

    def test_base_approval_survives_failed_other_lint_without_waiving_it(self):
        self.write("bases/orc-dev/test/fixture.txt", "review me")
        self.run_command("git", "add", "bases")
        self.approve_base()
        self.write(SOURCE, "(declare forward)")
        self.run_command("git", "add", SOURCE)
        self.assertIn("declare is banned", self.commit(succeeds=False))
        self.write(SOURCE, "(defreadmodel :sample rows {:version 1} [s e] s)")
        self.run_command("git", "add", SOURCE)
        self.commit()

    def test_all_base_file_categories_modes_and_moves_require_review(self):
        self.commit()
        for name in ("bases/orc-dev/src/core.clj", "bases/orc-dev/test/fixture.txt",
                     "bases/orc-dev/deps.edn", "bases/new-base/resources/file with space.bin"):
            with self.subTest(path=name):
                self.write(name, "")
                self.run_command("git", "add", name)
                self.commit(succeeds=False)
                self.approve_base()
                self.commit()
        binary = self.repo / "bases/new-base/resources/file with space.bin"
        binary.write_bytes(bytes(range(256)))
        self.run_command("git", "add", str(binary))
        self.commit(succeeds=False)
        self.approve_base()
        self.run_command("git", "update-index", "--chmod=+x", str(binary))
        self.commit(succeeds=False)
        self.approve_base()
        self.commit()
        self.run_command("git", "mv", "bases/orc-dev/deps.edn", "moved-out.edn")
        self.commit(succeeds=False)
        self.approve_base()
        self.commit()
        self.run_command("git", "mv", "moved-out.edn", "bases/orc-dev/returned.edn")
        self.commit(succeeds=False)
        self.approve_base()
        self.commit()
        self.run_command("git", "rm", "bases/orc-dev/returned.edn")
        self.commit(succeeds=False)
        self.approve_base()
        self.commit()

    def test_unstaged_base_work_is_ignored_and_staged_gate_cannot_be_masked(self):
        self.write("bases/orc-dev/resources/untracked.txt", "untracked")
        self.commit()
        self.run_command("git", "add", "bases")
        (self.repo / "bases/orc-dev/resources/untracked.txt").unlink()
        self.write("scripts/lint_base_changes.bb", '(println "unstaged bypass")')
        self.assertIn("Review the base change", self.commit(succeeds=False))

    def test_approval_rejects_missing_evidence_and_malformed_receipts(self):
        self.assertNotEqual(self.base_review("approve", "0" * 64, "Human", "Reason", check=False).returncode, 0)
        self.write("bases/orc-dev/deps.edn", "")
        self.run_command("git", "add", "bases")
        result = self.base_review(check=False)
        fingerprint = re.search(r"Review fingerprint: ([0-9a-f]{64})", result.stdout).group(1)
        for who, reason in (("", "Reason"), ("Human", " ")):
            self.assertNotEqual(self.base_review("approve", fingerprint, who, reason, check=False).returncode, 0)
        receipt = self.run_command("git", "rev-parse", "--git-path", "orc-base-review.edn").stdout.strip()
        self.write(receipt, "not-a-receipt")
        self.assertIn("Invalid base review receipt", self.commit(succeeds=False))
        self.base_review("approve", fingerprint, "Fixture Human", "Reviewed exact change")
        self.commit()

    def test_parent_change_invalidates_an_otherwise_identical_base_proposal(self):
        self.commit()
        name = "bases/orc-dev/deps.edn"
        self.write(name, "")
        self.run_command("git", "add", name)
        original = self.approve_base()
        self.run_command("git", "restore", "--staged", name)
        self.write("unrelated.txt", "advance parent")
        self.run_command("git", "add", "unrelated.txt")
        self.commit()
        self.run_command("git", "add", name)
        self.commit(succeeds=False)
        self.assertNotEqual(self.base_review("approve", original, "Human", "Stale", check=False).returncode, 0)

    def test_base_receipt_is_worktree_local_and_does_not_approve_other_index(self):
        self.commit()
        name = "bases/orc-dev/deps.edn"
        self.write(name, "")
        self.run_command("git", "add", name)
        self.approve_base()
        other = Path(self.temp.name) / "other worktree"
        self.run_command("git", "worktree", "add", "--detach", str(other), "HEAD")
        (other / "bases/orc-dev").mkdir(parents=True)
        (other / name).write_text("")
        self.run_command("git", "add", name, cwd=other)
        result = self.run_command("bb", "scripts/lint_base_changes.bb", cwd=other, check=False)
        self.assertIn("BLOCKED", result.stdout)
        self.assertEqual(result.returncode, 1)

    def test_review_fails_closed_outside_git_and_with_unmerged_entries(self):
        result = self.base_review("check", str(self.snapshots), check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("Cannot inspect Git state", result.stdout)
        self.assertNotEqual(self.base_review("unexpected-option", check=False).returncode, 0)
        self.commit()
        blob = self.run_command("git", "rev-parse", "HEAD:" + SOURCE).stdout.strip()
        entries = "".join(f"100644 {blob} {stage}\tbases/conflict.txt\n" for stage in (1, 2, 3))
        subprocess.run(["git", "update-index", "--index-info"], cwd=self.repo,
                       env=self.env, text=True, input=entries, check=True, capture_output=True)
        before = self.run_command("git", "ls-files", "--stage").stdout
        result = self.base_review(check=False)
        self.assertNotEqual(result.returncode, 0)
        self.assertIn("unmerged index entries", result.stdout)
        self.assertEqual(self.run_command("git", "ls-files", "--stage").stdout, before)

    def test_clean_commit_and_repeatable_subdirectory_install(self):
        self.run_command("sh", "../scripts/install-hooks.sh", cwd=self.repo / "components")
        self.assertEqual(self.run_command("git", "config", "--local", "core.hooksPath").stdout.strip(), ".githooks")
        output = self.commit()
        for message in ("service component allowlist satisfied", "Grain declarations use service-owned canonical files", "read-model versions are 1", "no new :any"):
            self.assertIn(message, output)

    def test_each_lint_blocks_commit(self):
        cases = (
            (SOURCE, "#_(def inactive 1)", "Reader discards"),
            (SOURCE, "(comment (def inactive 1))", "Comment forms are banned"),
            (SOURCE, "(defreadmodel :sample rows {:version 2} [s e] s)", "version 1"),
            (SCHEMAS, "(defschemas schemas {:fixture/event [:map [:field :any]]})", "NEW :any"),
            (SERVICE + "interface/promo/schemas.clj", "(ns duplicate.schemas)", "Only one schemas.clj"),
            (SERVICE + "core/extra/commands.clj", "(ns duplicate.commands)", "Only one commands.clj"),
            ("components/orc-service/src/helper.clj", "(defcommand :fixture misplaced {})", "Grain declaration placement"),
            ("bases/orc-dev/src/commands.clj", "(defcommand :fixture misplaced {})", "outside service components"),
            ("projects/web-api/src/queries.clj", "(defquery :fixture misplaced {})", "outside service components"),
            (SOURCE, "(defn misplaced [] 1)", "Backend function misplaced"),
            (SERVICE + "core/logic.clj", "(ai.obney.grain.event-store-v3.interface/read store {})", "Grain runtime reference"),
            (SERVICE + "core/nested/logic.clj", "(ns duplicate.logic)", "Only one logic.clj"),
        )
        for name, content, message in cases:
            with self.subTest(lint=message):
                original = (self.repo / name).read_text() if (self.repo / name).exists() else None
                self.write(name, content)
                self.run_command("git", "add", name)
                self.assertIn(message, self.commit(succeeds=False))
                if original is None:
                    (self.repo / name).unlink()
                else:
                    self.write(name, original)
                self.run_command("git", "add", "-u")

    def test_logic_placement_checks_staged_functions_and_allows_views(self):
        self.write(SOURCE, "(defn helper [] 1)")
        self.run_command("git", "add", SOURCE)
        self.write(SOURCE, "(def data 1)")
        self.assertIn("Backend function helper", self.commit(succeeds=False))
        self.run_command("git", "add", SOURCE)
        self.write(SERVICE + "core/logic.clj", "(ns domain.logic) (defn helper [] 1)")
        self.write(SERVICE + "interface.clj", "(ns domain.api (:require [domain.logic :as l])) (defn helper [] (l/helper))")
        self.write(SERVICE + "views.clj", "(ns ai.obney.orc.orc-service.views) (defn page [] [:div])")
        self.write("components/orc-service/test/helper_test.clj", "(defn fixture [] 1)")
        self.run_command("git", "add", ".")
        self.commit()

    def test_unstaged_fix_cannot_hide_staged_violation(self):
        name = SOURCE
        self.write(name, "(defreadmodel :sample rows {:version 2} [s e] s)")
        self.run_command("git", "add", name)
        self.write(name, "(defreadmodel :sample rows {:version 1} [s e] s)")
        self.assertIn("version 1", self.commit(succeeds=False))

    def test_unstaged_schema_file_cannot_satisfy_staged_layout(self):
        self.run_command("git", "rm", "--cached", "--", SCHEMAS)
        self.assertTrue((self.repo / SCHEMAS).exists())
        self.assertIn("Every service requires", self.commit(succeeds=False))

    def test_unapproved_service_blocks_even_if_removed_from_working_tree(self):
        name = "components/unapproved-service/deps.edn"
        self.write(name, '{:paths ["src"]}')
        self.run_command("git", "add", name)
        (self.repo / name).unlink()
        (self.repo / name).parent.rmdir()
        output = self.commit(succeeds=False)
        self.assertIn("unapproved-service: error:", output)
        self.assertIn("README.md#service-ownership-and-base-review", output)

    def test_allowed_service_commits_and_untracked_service_is_excluded(self):
        self.write("components/orc-service/deps.edn", '{:paths ["src"]}')
        self.run_command("git", "add", "components/orc-service")
        self.write("components/untracked-service/deps.edn", '{:paths ["src"]}')
        self.commit()

    def test_unstaged_and_untracked_violations_do_not_block_clean_index(self):
        name = SOURCE
        self.write(name, "(defreadmodel :sample rows {:version 1} [s e] s)")
        self.run_command("git", "add", name)
        self.write(name, "(defreadmodel :sample rows {:version 2} [s e] s)")
        self.write("components/fixture/src/untracked.clj", "(defreadmodel :sample other {:version 3} [s e] s)")
        self.write(SERVICE + "core/nested/read_models.clj", "(ns untracked.duplicate)")
        self.commit()

    def test_missing_babashka_blocks_commit(self):
        commands = Path(self.temp.name) / "commands"
        commands.mkdir()
        for name in ("git", "sh", "dirname", "mktemp", "mkdir", "rm"):
            (commands / name).symlink_to(shutil.which(name))
        self.assertIn("require Babashka", self.commit(succeeds=False, env=dict(self.env, PATH=str(commands))))


if __name__ == "__main__":
    unittest.main()
