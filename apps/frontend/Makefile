.PHONY: deploy build-local

# 배포 대상 서버 목록
DEPLOY_SERVERS ?= ktb-fe01
DEPLOY_PATH ?= /home/ubuntu/ktb-chat-frontend

# 로컬에서 프로덕션 빌드
build-local:
	@echo "🏗️  Building locally..."
	npm run build:production
	@echo "✅ Local build completed!"

deploy:
	@echo "📦 Deploying to remote servers..."
	@for server in $(DEPLOY_SERVERS); do \
		echo "→ Deploying to $$server..."; \
		ssh $$server "mkdir -p $(DEPLOY_PATH)"; \
		echo "  📁 Copying standalone build..."; \
		rsync -avz --delete --exclude='*.log' --exclude='.env*' --exclude="server.pid" --exclude='/package.json' .next/standalone/ $$server:$(DEPLOY_PATH)/; \
		echo "  📁 Copying static files..."; \
		rsync -avz --delete .next/static $$server:$(DEPLOY_PATH)/.next/; \
		echo "  📁 Copying public files..."; \
		rsync -avz --delete public $$server:$(DEPLOY_PATH)/; \
		echo "  📁 Copying restart script..."; \
		rsync -avz restart.sh $$server:$(DEPLOY_PATH)/; \
		echo "  🔄 Restarting server..."; \
		ssh $$server "cd $(DEPLOY_PATH) && chmod +x restart.sh && ./restart.sh"; \
		echo "✅ Deployment to $$server completed!"; \
	done
	@echo "✅ All deployments completed!"


DOCKER_USER ?= michael568
IMAGE_NAME = ktb-chat-frontend
TAG ?= latest
# 실제 배포할 백엔드 주소
PROD_API_URL ?= https://chat.goorm-ktb-019.goorm.team
PROD_SOCKET_URL ?= https://chat.goorm-ktb-019.goorm.team

docker-build:
	docker build \
		--platform linux/amd64 \
		--build-arg NEXT_PUBLIC_API_URL=$(PROD_API_URL) \
		--build-arg NEXT_PUBLIC_SOCKET_URL=$(PROD_SOCKET_URL) \
		-t $(DOCKER_USER)/$(IMAGE_NAME):$(TAG) .

docker-push:
	docker push $(DOCKER_USER)/$(IMAGE_NAME):$(TAG)

# 한 번에 실행: make docker-deploy
docker-deploy: docker-build docker-push
	@echo "이미지 배포 준비 완료. EC2에서 'docker pull ...' 후 실행하세요."