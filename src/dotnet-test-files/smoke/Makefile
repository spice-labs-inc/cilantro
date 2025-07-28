STAGE_DIR = ../../../test-files/smoke/

all: stage

stage: code
	@ mkdir -p $(STAGE_DIR)
	@ cp bin/Debug/net8.0/Smoke.dll $(STAGE_DIR)

code: *.cs
	dotnet build

clean:
	dotnet clean
	rm -rf bin/*
	rm -rf obj/*
