// X64Fixture — a PE32+ (x64-targeted) managed assembly for the corpus.
//
// The corpus's nuget assemblies are AnyCPU (PE32); PE32+ mapping needs a
// real x64-targeted specimen. Compiled inside the pinned SDK image with
// PlatformTarget=x64 by scripts/GoldenDumper's fetch step.

public static class X64Fixture
{
    public static int Sum(int n)
    {
        int total = 0;
        for (int i = 0; i < n; i++)
        {
            total += i;
        }
        return total;
    }

    public static int WithCatch(int x)
    {
        try
        {
            if (x == 0)
            {
                throw new System.ArgumentException("zero");
            }
            return x * 2;
        }
        catch (System.ArgumentException)
        {
            return -1;
        }
    }
}
