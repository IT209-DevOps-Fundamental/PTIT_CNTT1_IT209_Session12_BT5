# Báo Cáo Chẩn Đoán và Khắc Phục 3 Sự Cố Đứt Gãy Pipeline CI/CD Thực Tế

## 1. Giới thiệu tổng quan
- **Tệp kịch bản sự cố**: [`.github/workflows/broken-pipeline.yml`](.github/workflows/broken-pipeline.yml)
- **Mục tiêu luồng**: Tự động hóa quá trình kiểm thử đơn vị (`Unit Tests`), đóng gói tệp thực thi (`Gradle bootJar`), thiết lập môi trường Docker Buildx, đăng nhập GitHub Container Registry (`ghcr.io`) và phát hành Docker Image chính thức.
- **Hiện trạng ban đầu**: Pipeline bị đứt gãy hoàn toàn (Status: `Failure`), dừng lại ngay từ bước chạy Unit Tests do chuỗi 3 lỗi cấu hình kinh điển trong CI/CD.

---

## 2. Chi tiết chẩn đoán và khắc phục 3 sự cố

### Sự cố 1: Gradle Wrapper thiếu quyền thực thi (`Permission Denied`)
#### Dấu hiệu lỗi thực tế trên log GitHub Actions:
```text
##[group]Run ./gradlew test
./gradlew test
shell: /usr/bin/bash -e {0}
/home/runner/work/_temp/f6757266-d118-4c03-a152-dec0848d5179.sh: line 1: ./gradlew: Permission denied
##[error]Process completed with exit code 126.
```

#### Phân tích nguyên nhân gốc rễ (Root Cause Analysis):
- Mã lỗi **`Exit Code 126`** trong môi trường Linux biểu thị lỗi: *Command invoked cannot execute* (tệp lệnh tồn tại nhưng không thể thực thi do thiếu quyền).
- Tệp `gradlew` được khởi tạo và commit từ môi trường máy phát triển chạy hệ điều hành Windows. Hệ thống tệp trên Windows mặc định lưu trữ file mode trong Git là `100644` (chỉ có quyền đọc/ghi `-rw-r--r--`, không có cờ thực thi `+x`).
- Khi GitHub Actions Runner (môi trường Ubuntu Linux) thực hiện clone repository và gọi lệnh shell `./gradlew test`, nhân Linux từ chối thực thi do tệp thiếu execute bit.

#### Giải pháp khắc phục (Resolution):
Thêm bước cấp quyền thực thi `run: chmod +x gradlew` ngay sau bước cài đặt Java (`actions/setup-java@v5`) và trước khi gọi bất kỳ lệnh Gradle nào:
```yaml
- name: Grant execute permission for gradlew
  run: chmod +x gradlew
```

---

### Sự cố 2: Lỗi đăng nhập Docker Registry do sai tên biến Secret (`Authentication Failed`)
#### Dấu hiệu lỗi thực tế trên log GitHub Actions:
```text
Error: Cannot perform an interactive login from a non TTY device
Error: Username and password are required
Error response from daemon: Get "https://ghcr.io/v2/": unauthorized
```

#### Phân tích nguyên nhân gốc rễ (Root Cause Analysis):
- Trong bước `docker/login-action@v3`, tham số `password` được khai báo là:
  ```yaml
  password: ${{ secrets.REGISTRY_TOEKN }}
  ```
  Biến bí mật đã bị gõ sai chính tả: chữ `TOKEN` bị gõ nhầm thành `TOEKN`.
- Khi GitHub Actions Runner nội suy biểu thức `${{ secrets.REGISTRY_TOEKN }}`, do tên secret này không tồn tại trong cấu hình Secrets của Repository, GitHub tự động trả về chuỗi rỗng `""`.
- Khi truyền mật khẩu rỗng vào lệnh đăng nhập, máy chủ `ghcr.io` từ chối xác thực (`401 Unauthorized`) hoặc Docker CLI báo lỗi không thể thực hiện đăng nhập tương tác trong môi trường không có TTY (`non TTY device`).

#### Giải pháp khắc phục (Resolution):
1. Kiểm tra lại chính tả tên biến secret trong cấu hình Repository Secrets, đảm bảo trùng khớp ký tự:
   ```yaml
   password: ${{ secrets.REGISTRY_TOKEN }}
   ```
2. Đồng thời, cấu hình chuẩn mực CI/CD khuyến nghị sử dụng token tích hợp sẵn `${{ secrets.GITHUB_TOKEN }}` kết hợp với khai báo quyền ở cấp Job `permissions: packages: write`. Token này được sinh tự động theo từng run, luôn có đủ phạm vi đẩy image lên GHCR mà không lo rủi ro hết hạn hay thiếu scope:
   ```yaml
   password: ${{ secrets.GITHUB_TOKEN }}
   ```

---

### Sự cố 3: Lỗi ngữ cảnh đóng gói Docker (`Build Context Error`)
#### Dấu hiệu lỗi thực tế trên log GitHub Actions:
```text
ERROR: failed to solve: failed to read dockerfile: open /var/lib/docker/.../wrong-path/Dockerfile: no such file or directory
ERROR: failed to solve: failed to read context: directory not found: ./wrong-context
```

#### Phân tích nguyên nhân gốc rễ (Root Cause Analysis):
- Trong bước `docker/build-push-action@v5`, tham số ngữ cảnh và đường dẫn Dockerfile bị cấu hình sai:
  ```yaml
  context: ./wrong-context
  file: ./wrong-path/Dockerfile
  ```
- Docker BuildKit yêu cầu một build context hợp lệ chứa toàn bộ mã nguồn cần đóng gói và đường dẫn chính xác tới tệp `Dockerfile`. Do thư mục `./wrong-context` và tệp `./wrong-path/Dockerfile` không tồn tại trong repository, quá trình build bị dừng khẩn cấp trước khi khởi tạo image.

#### Giải pháp khắc phục (Resolution):
Cấu hình lại tham số ngữ cảnh trỏ chính xác về thư mục gốc của repository (`context: .`) và đường dẫn tệp Dockerfile (`file: ./Dockerfile`):
```yaml
- name: Build and push Docker image
  uses: docker/build-push-action@v5
  with:
    context: .
    file: ./Dockerfile
    push: true
    tags: ${{ env.IMAGE_NAME }}:latest
```

---

## 3. Bảng so sánh Before vs After

| Thành phần | Trước khi sửa (Broken) | Sau khi sửa (Fixed) | Kết quả cải thiện |
| :--- | :--- | :--- | :--- |
| **Quyền thực thi Gradle** | Thiếu bước `chmod +x gradlew` | `run: chmod +x gradlew` | Chạy lệnh `./gradlew test` và `bootJar` thành công, không còn lỗi `Permission denied (Exit code 126)`. |
| **Secret Đăng nhập Registry** | `${{ secrets.REGISTRY_TOEKN }}` (sai chính tả) | `${{ secrets.GITHUB_TOKEN }}` (hoặc `${{ secrets.REGISTRY_TOKEN }}`) | Đăng nhập thành công vào `ghcr.io`, thông báo `Login Succeeded`. |
| **Docker Build Context & File** | `context: ./wrong-context`<br>`file: ./wrong-path/Dockerfile` | `context: .`<br>`file: ./Dockerfile` | BuildKit tìm thấy Dockerfile, đóng gói mã nguồn và push image lên GHCR thành công. |

---

## 4. Kịch bản hoàn chỉnh sau khi khắc phục
Tệp: [`.github/workflows/broken-pipeline.yml`](.github/workflows/broken-pipeline.yml)

```yaml
name: CI/CD Pipeline

on:
  push:
    branches:
      - main
  workflow_dispatch:

jobs:
  test-and-build:
    name: Test and Build Docker Image
    runs-on: ubuntu-latest
    permissions:
      contents: read
      packages: write

    steps:
      - name: Checkout repository
        uses: actions/checkout@v5

      - name: Set up Java JDK 17
        uses: actions/setup-java@v5
        with:
          java-version: '17'
          distribution: 'temurin'
          cache: 'gradle'

      # Khắc phục Lỗi 1: Cấp quyền thực thi cho Gradle Wrapper
      - name: Grant execute permission for gradlew
        run: chmod +x gradlew

      - name: Run Unit Tests
        run: ./gradlew test

      - name: Build Application JAR
        run: ./gradlew bootJar

      - name: Set lower case repository name
        run: |
          echo "IMAGE_NAME=ghcr.io/${GITHUB_REPOSITORY,,}" >> $GITHUB_ENV

      - name: Set up Docker Buildx
        uses: docker/setup-buildx-action@v3

      # Khắc phục Lỗi 2: Sửa đúng tên biến Secret đăng nhập Registry
      - name: Log in to Docker Registry
        uses: docker/login-action@v3
        with:
          registry: ghcr.io
          username: ${{ github.actor }}
          password: ${{ secrets.GITHUB_TOKEN }}

      # Khắc phục Lỗi 3: Cấu hình đúng context và đường dẫn file Dockerfile
      - name: Build and push Docker image
        uses: docker/build-push-action@v5
        with:
          context: .
          file: ./Dockerfile
          push: true
          tags: ${{ env.IMAGE_NAME }}:latest
```

---

## 5. Kết quả kiểm tra và xác nhận

- **Repository**: [IT209-DevOps-Fundamental/PTIT_CNTT1_IT209_Session12_BT5](https://github.com/IT209-DevOps-Fundamental/PTIT_CNTT1_IT209_Session12_BT5)
- **Workflow Run thất bại ban đầu**: [Run #1 (ID: 38561198589)](https://github.com/IT209-DevOps-Fundamental/PTIT_CNTT1_IT209_Session12_BT5/actions/runs/38561198589) - Lỗi `Permission denied (Exit code 126)`.
- **Workflow Run thành công sau khi sửa**: [Run #3 (ID: 38561704935)](https://github.com/IT209-DevOps-Fundamental/PTIT_CNTT1_IT209_Session12_BT5/actions/runs/38561704935) - Hoàn thành tích xanh (**Success**) trong **1m 16s**.
