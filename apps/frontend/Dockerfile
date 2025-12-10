# Stage 1: Builder
FROM node:22-alpine AS builder
WORKDIR /app

# 패키지 파일 복사
COPY package.json ./

# 의존성 설치 (빌드에 필요한 devDependencies 포함)
RUN npm i

# 소스 코드 복사
COPY . .

# 외부에서 넣어주는 값을 받겠다고 선언 (ARG)
ARG NEXT_PUBLIC_API_URL
ARG NEXT_PUBLIC_SOCKET_URL

# 받은 값을 빌드 도중 사용할 수 있게 환경 변수로 설정 (ENV)
# .env 파일보다 이 값이 우선순위 더 높음
ENV NEXT_PUBLIC_API_URL=$NEXT_PUBLIC_API_URL
ENV NEXT_PUBLIC_SOCKET_URL=$NEXT_PUBLIC_SOCKET_URL

# Next.js 빌드
RUN npm run build

# Stage 2: Runner
FROM node:22-alpine AS runner
WORKDIR /app

# 보안을 위한 non-root 사용자 생성
RUN addgroup --system --gid 1001 nodejs && \
    adduser --system --uid 1001 nextjs

# 필요한 파일만 복사
COPY --from=builder /app/public ./public
COPY --from=builder /app/.next/standalone ./
COPY --from=builder /app/.next/static ./.next/static

# 파일 권한 설정
RUN chown -R nextjs:nodejs /app

USER nextjs

EXPOSE 3000

ENV PORT=3000
ENV NODE_ENV=production

CMD ["node", "server.js"]

