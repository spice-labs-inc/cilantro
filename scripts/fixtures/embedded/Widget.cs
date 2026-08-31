// EmbeddedPdbFixture source — the pinned embedded-source fixture.
// The marker line must survive the embedded-PDB round trip exactly.
namespace EmbeddedFixture {
    public static class Widget {
        public const string Marker = "EMBEDDED_SOURCE_FIXTURE_MARKER_42";
        public static int Compute(int value) {
            return value * 2 + 1;
        }
    }
}
