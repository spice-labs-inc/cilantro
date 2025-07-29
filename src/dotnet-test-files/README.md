# dotnet-test-files
This directory will contain test files and projects to generate .NET assemblies to be
used in tests

## Creating a Test

There are 3 basic steps for creating a new test:

1. Create the .NET assembly
2. Stage the assembly for a unit test
3. Refer to the assembly from the test

## Creating a .NET assembly

First, you need the .NET ecosystem. The easiest way to get that is to install Visual Studio Code and add the extensions ".NET Install Tool", "C#" and "C# Dev Kit". Once all of this is done, open a shell and execute `which dotnet` and if you get a meaningful result, then you should be good to go.

1. Create a new directory for your assembly source code in src/dotnet-test-files
2. In that directory, create a new .NET project. There are two easy ways to do that. The first is on the command line with `dotnet new`. The second is from VS Code - create a new window and do `ctrl-shift-P` which will replace the Search box with a command list. The command you will want is ".NET: New Project" by starting to type it out, it will filter down to this.
3. Write your code
4. Compile it. This can be done from the command line with `dotnet build` or from within VS Code. On successful build, you will have a folder named `bin/Debug/<platform>` and it will contain `<project-name>.dll` which is the output assembly.
5. Create a new directory within the directory `test-files` named after your assmbly. Copy the file from the output directory from step 4 into this new directory.
6. When you open a PR, make sure that the dll in `test-files` has been staged and the bin directory's contents has **NOT** be staged.

## Notes on the `dotnet` Command
Like the command `git` `dotnet` is a starting point for a vast array of tools. The main ones that you will care about are `dotnet new` and `dotnet build`. Either of these commands can be invoked with `--help` in order to see the options (sadly, not all commands are complete in this regard).

For `dotnet new` you typically follow it with a short name of a project template from which you want to create your code. The two most common ones are `classlib` and `console` and for most of these tests, you will probably be using `classlib`.

`dotnet new classlib` will create all the infratructure needed for a project that will be in your current working directory and will be named after your CWD. So if you're in `path-to/shmenge-brothers` and do `dotnet new classlib` it will create a project file named `shmenge-brothers.csproj` and a file named `Class1.cs` which will contain an empty class in the namespace `schmenge-brothers`. As a general rule, dotnet assemblies and namespaces start with a capital letter and don't have other punctuation. You can avoid this issue by using `--name` to name the assembly and the namespace. In this case, you would want `dotnet new classlib --name SchmengeBrothers`. This will also create a directory named `SchmengeBrothers`, so heads up.
When in doubt, you can add the option `--dry-run` to any `dotnet new` invocation to see what it will do.

## Things you might care about
There are three main things that will determine the nature of the output assembly:
1. The dotnet SDK that you are using. As of this writing, the current is 8.0, but they're evolving pretty quickly.
2. The version of C# that the compiler will accept.
3. The project type that you are generating

For example, a project of type ios that is generating code for an iOS application will have a lot of iOS specific things attached to it than simply code. Different versions of the C# compiler will generate different output. Different SDKs will inject different dependencies in the output assembly.

## Example
```bash
cd src/dotnet-test-files # assuming you're in the repository root
dotnet new classlib --name MyLib # make the project
mv MyLib mylib
cd mylib
dotnet build # compile the code
mkdir ../../../test-files/mylib # this is for the staged output
cp bin/Debug/net8.0/MyLib.dll ../../../test-files/mylib/
```

What you should really do is create a Makefile to do all of this for you. In the test assmebly smoke, there is a Makefile which compiles the code and stages the output. It also includes a clean step using `dotnet clean`. You should clean before you stage files for a PR.

## Referencing Test Files

From within a test, you can use `System.getProperty("user.dir")` to get the path for the scala project directory. From there `../../test-files/<your-stage-directory>/<your-project>.dll` will give you the test file.

When in doubt, look at the test `smoke exists` which has code to get a basic test. Almost assuredly, I will add a handy function like:
```scala
def getTestFile(stageName: String, dllName: String, platform: String = "") : Path
```

## Organizing For the Future
Since we are likely to need test files that use the same source code but generate for different target SDKs (or heaven forbid, C# version), it's probably best to organize the stage directory as:
```
testname
    dotnet-version-x
        TestName.dll
    dotnet-version-y
        csharp-version-x
            TestName.dll
        csharp-version-y
            TestName.dll
    
    
```
And in this way, when I write `getTestFile` you can invoke it with `getTestFile("testname", "TestName.dll", "dotnet-version-y/csharp-verion-x")` and get expected results.
