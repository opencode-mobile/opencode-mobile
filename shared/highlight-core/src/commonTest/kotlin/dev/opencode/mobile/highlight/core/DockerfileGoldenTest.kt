package dev.opencode.mobile.highlight.core

import kotlin.test.Test

/**
 * Hand-written Dockerfile fixtures: the contract for its lexer. One escaped string per line keeps
 * `$`, backslashes, CRLF, and lone surrogates exact.
 */
class DockerfileGoldenTest {
    @Test
    fun instructionsAndForms() {
        assertGolden(
            "docker",
            goldenLines(
                "# syntax=docker/dockerfile:1",
                "FROM node:22-alpine AS build",
                "ARG VERSION=1.0",
                "ENV NODE_ENV=production PATH=\"/app/bin:\$PATH\"",
                "WORKDIR /app",
                "COPY --from=build --chown=app package.json ./",
                "EXPOSE 8080/tcp 9000",
                "CMD [\"node\", \"server.js\"]",
                "ENTRYPOINT [\"/bin/sh\", \"-c\"]",
                "LABEL org.opencontainers.image.title=\"app\" version=\${VERSION}",
                "USER app",
            ),
            goldenLines(
                "«Comment|#» «Keyword|syntax»«Operator|=»«String|docker/dockerfile:1»",
                "«Keyword|FROM» node:22-alpine «Keyword|AS» build",
                "«Keyword|ARG» «Variable|VERSION»«Operator|=»1.0",
                "«Keyword|ENV» «Variable|NODE_ENV»«Operator|=»production «Variable|PATH»«Operator|=»«String|\"/app/bin:»«Variable|\$PATH»«String|\"»",
                "«Keyword|WORKDIR» /app",
                "«Keyword|COPY» «Constant|--from»=build «Constant|--chown»=app package.json ./",
                "«Keyword|EXPOSE» 8080/tcp «Number|9000»",
                "«Keyword|CMD» «Punctuation|[»«String|\"node\"»«Punctuation|,» «String|\"server.js\"»«Punctuation|]»",
                "«Keyword|ENTRYPOINT» «Punctuation|[»«String|\"/bin/sh\"»«Punctuation|,» «String|\"-c\"»«Punctuation|]»",
                "«Keyword|LABEL» «Variable|org.opencontainers.image.title»«Operator|=»«String|\"app\"» «Variable|version»«Operator|=»«Variable|\${VERSION}»",
                "«Keyword|USER» app",
            ),
        )
    }

    @Test
    fun shellFormAndContinuations() {
        assertGolden(
            "dockerfile",
            goldenLines(
                "RUN --mount=type=cache,target=/root/.npm npm ci --omit=dev \\",
                "    # comments inside a continuation are skipped",
                "    && echo \"built \$VERSION\" \\",
                "",
                "    && rm -rf /tmp/*",
                "run apk add --no-cache git",
            ),
            goldenLines(
                "«Keyword|RUN» «Constant|--mount»=type=cache,target=/root/.npm «Function|npm» ci «Constant|--omit»=dev \\",
                "    «Comment|# comments inside a continuation are skipped»",
                "    «Operator|&&» «Function|echo» «String|\"built »«Variable|\$VERSION»«String|\"» \\",
                "",
                "    «Operator|&&» «Function|rm» «Constant|-rf» /tmp/*",
                "«Keyword|run» «Function|apk» add «Constant|--no-cache» git",
            ),
        )
    }

    @Test
    fun heredocsHealthcheckAndOnbuild() {
        assertGolden(
            "containerfile",
            goldenLines(
                "RUN <<EOF",
                "apt-get update",
                "apt-get install -y curl",
                "EOF",
                "HEALTHCHECK --interval=30s --timeout=3s CMD curl -f http://localhost/ || exit 1",
                "HEALTHCHECK NONE",
                "ONBUILD RUN make",
                "SHELL [\"/bin/bash\", \"-c\"]",
            ),
            goldenLines(
                "«Keyword|RUN» «Operator|<<»«Keyword|EOF»",
                "«String|apt-get update»",
                "«String|apt-get install -y curl»",
                "«Keyword|EOF»",
                "«Keyword|HEALTHCHECK» «Constant|--interval»=30s «Constant|--timeout»=3s «Keyword|CMD» «Function|curl» «Constant|-f» http://localhost/ «Operator|||» «Keyword|exit» «Number|1»",
                "«Keyword|HEALTHCHECK» «Keyword|NONE»",
                "«Keyword|ONBUILD» «Keyword|RUN» «Function|make»",
                "«Keyword|SHELL» «Punctuation|[»«String|\"/bin/bash\"»«Punctuation|,» «String|\"-c\"»«Punctuation|]»",
            ),
        )
    }

    @Test
    fun directivesOnlyAtTheTop() {
        assertGolden(
            "docker",
            goldenLines(
                "# escape=`",
                "# check=skip=all",
                "RUN echo a `",
                "  b",
                "# syntax=not/a/directive",
                "FROM scratch",
            ),
            goldenLines(
                "«Comment|#» «Keyword|escape»«Operator|=»«String|`»",
                "«Comment|#» «Keyword|check»«Operator|=»«String|skip=all»",
                "«Keyword|RUN» «Function|echo» a «Punctuation|`»",
                "  «Function|b»",
                "«Comment|# syntax=not/a/directive»",
                "«Keyword|FROM» scratch",
            ),
        )
    }

    @Test
    fun legacyEnvAndCrlf() {
        assertGolden(
            "docker",
            "ENV PATH /usr/bin:\$PATH\r\nRUN echo \"😀 \uD83D\"\r\n",
            goldenLines(
                "«Keyword|ENV» «Variable|PATH» /usr/bin:«Variable|\$PATH»␍",
                "«Keyword|RUN» «Function|echo» «String|\"😀 ⟨D83D⟩\"»␍",
                "",
            ),
        )
    }
}
