/** @type {import('next').NextConfig} */
const nextConfig = {
  reactStrictMode: false, // 에러 처리 문제 해결을 위해 일시적으로 비활성화
  transpilePackages: ['@vapor-ui/core', '@vapor-ui/icons'],
  output: 'export', // 정적 배포를 위해 standalone -> export로 변경
  images: {
    unoptimized: true,
  },
  trailingSlash: true,
  // monorepo에서 standalone 빌드 시 중첩 경로 방지
  outputFileTracingRoot: __dirname,
  // 개발 환경에서의 에러 오버레이 설정
  devIndicators: {
    position: 'bottom-right'
  },
  // 개발 환경에서만 더 자세한 에러 로깅
  ...(process.env.NODE_ENV === 'development' && {
    experimental: {
      forceSwcTransforms: true
    }
  })
};

module.exports = nextConfig;
