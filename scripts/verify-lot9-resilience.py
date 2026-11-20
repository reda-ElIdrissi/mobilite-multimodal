"""Validate circuit isolation and recovery of the real aerial operator."""
from pathlib import Path
import runpy

search = runpy.run_path(str(Path(__file__).with_name("verify-lot3.py")))
query = search["query"]
compose = search["compose"]


def main():
    search["await_air"]()
    compose("stop", "air")
    try:
        for _ in range(5):
            result, _ = query("CAS", "RAB")
            assert result["partial"] and set(result["errors"]) == {"AIR"}, result
            assert any(offer["operator"] == "RAIL" for offer in result["offers"]), result
        result, elapsed = query("RAB", "PAR", expected=503, modes=["AVION"])
        assert set(result["errors"]) == {"AIR"} and elapsed < 2, (result, elapsed)
    finally:
        compose("start", "air")
    search["await_air"]()
    print(f"Lot 9 resilience OK: rail isolated, open air circuit in {elapsed:.2f} s, recovery confirmed.")


if __name__ == "__main__":
    main()
