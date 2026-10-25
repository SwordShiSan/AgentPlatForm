import importlib.util
from pathlib import Path
import unittest

spec = importlib.util.spec_from_file_location("resource_envelopes", Path(__file__).parents[1] / "scripts/check-resource-envelopes.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class ResourceEnvelopesTest(unittest.TestCase):
    def test_go_limit_surviving_container_reduction_is_rejected_without_secret_output(self):
        config = {"services": {"s3": {"mem_limit": "160m", "memswap_limit": "160m",
                  "environment": {"GOMEMLIMIT": "192MiB", "PRIVATE_SENTINEL": "must-not-appear"}}}}
        result = module.inspect(config, ["s3"])
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("GO_SOFT_LIMIT_MUST_BE_BELOW_CONTAINER_LIMIT", result["findings"][0]["code"])
        self.assertNotIn("must-not-appear", str(result))

    def test_headroom_does_not_claim_functional_capacity(self):
        config = {"services": {"runner": {"mem_limit": "320m", "memswap_limit": "320m",
                  "entrypoint": ["java", "-Xmx112m", "-jar", "/app/platform.jar"]}}}
        result = module.inspect(config, ["runner"])
        self.assertEqual("PASS", result["status"])
        self.assertIn("does not prove", result["scope"])

    def test_swap_and_heap_equal_container_are_rejected(self):
        config = {"services": {"api": {"mem_limit": "128m", "memswap_limit": "256m",
                  "entrypoint": ["java", "-Xmx128m"]}}}
        self.assertEqual(2, len(module.inspect(config, ["api"])["findings"]))

    def test_additional_runtime_budget_is_not_silently_omitted(self):
        config = {"services": {"base": {"mem_limit": "2200m", "memswap_limit": "2200m"}}}
        result = module.inspect(config, ["base"], reserved_mib=704)
        self.assertEqual("FAIL", result["status"])
        self.assertEqual("DECLARED_COMBINATION_EXCEEDS_BUDGET", result["findings"][0]["code"])

    def test_unresolved_shell_form_is_not_claimed_checked(self):
        config = {"services": {"api": {"mem_limit": "384m", "memswap_limit": "384m",
                  "entrypoint": "java -Xmx999m"}}}
        with self.assertRaisesRegex(ValueError, "RESOLVED_ARGUMENT_ARRAY_REQUIRED"):
            module.inspect(config, ["api"])


if __name__ == "__main__":
    unittest.main()
