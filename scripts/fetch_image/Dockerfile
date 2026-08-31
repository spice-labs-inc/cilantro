# Derived fetch image: pinned dotnet SDK digest + mono-devel (ilasm) +
# baked GoldenDumper. The bake means container runtime needs no build, no
# restore, no network (ADR-0006 supply-chain hardening): restore and
# publish happen at image build time against pinned package versions.
#
# The corpus fetch/golden regeneration must run as the invoking user
# (uid/gid) so files written to the mounted volume are owned correctly;
# apt-get therefore happens at image build time, not at run time.
#
# Build context is scripts/ (so GoldenDumper sources are reachable):
#   docker build -f scripts/fetch_image/Dockerfile -t cilantro-corpus-fetch:1 scripts

FROM mcr.microsoft.com/dotnet/sdk:8.0@sha256:bb32ba3ba3ea36e38572d9d8db76fa15f7cbf722f3f886e06bca6d528bd4fba8 AS build
WORKDIR /src
COPY GoldenDumper/GoldenDumper.csproj GoldenDumper/
COPY GoldenDumper/Program.cs GoldenDumper/
COPY GoldenDumper/GoldenWriter.cs GoldenDumper/
COPY GoldenDumper/Extraction.cs GoldenDumper/
COPY GoldenDumper/HostileZip.cs GoldenDumper/
RUN cd GoldenDumper \
 && dotnet restore --nologo \
 && dotnet publish -c Release -o /opt/goldendumper --nologo

FROM mcr.microsoft.com/dotnet/sdk:8.0@sha256:bb32ba3ba3ea36e38572d9d8db76fa15f7cbf722f3f886e06bca6d528bd4fba8
RUN apt-get update -qq \
 && apt-get install -y -qq --no-install-recommends mono-devel \
 && rm -rf /var/lib/apt/lists/*
COPY --from=build /opt/goldendumper /opt/goldendumper
