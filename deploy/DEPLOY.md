# Hướng dẫn deploy Cassava IoT lên server UET

Target server:
- **IP public**: 112.137.129.218
- **IP LAN**: 10.10.0.81
- **User**: uet
- **SSH port**: 22

Kiến trúc cuối cùng:

```
http://112.137.129.218/              → landing page (4 card cây trồng)
http://112.137.129.218/cassava/      → CassavaFE (React SPA)
http://112.137.129.218/cassava/api/* → proxy tới Spring Boot (127.0.0.1:8081)
```

---

## 1. Build artifact ở máy dev

### Build FE

```bash
cd CassavaFE
npm install
npm run build
# Output: CassavaFE/dist/
```

`dist/` chứa bundle đã inject `base: '/cassava/'` và env `VITE_API_BASE=/cassava/api`.

### Build BE

```bash
cd cassavaBE
mvn clean package -DskipTests
# Output: cassavaBE/target/demo1-0.0.1-SNAPSHOT.war
```

---

## 2. Chuẩn bị server lần đầu

SSH vào server:

```bash
ssh uet@112.137.129.218
```

Cài đặt gói:

```bash
sudo apt update
sudo apt install -y openjdk-17-jre-headless nginx rsync
```

Kiểm tra Java:

```bash
java -version   # phải là 17
```

Tạo cấu trúc thư mục:

```bash
sudo mkdir -p /opt/cassava/webroot/cassava
sudo mkdir -p /var/log/cassava
sudo chown -R uet:uet /opt/cassava /var/log/cassava
```

Mở firewall port 80 (nếu dùng ufw):

```bash
sudo ufw allow 80/tcp
sudo ufw status
```

---

## 3. Upload artifact từ máy dev

Từ máy dev (Git Bash trên Windows, hoặc WSL):

```bash
# Landing page
rsync -avz --delete landing/ uet@112.137.129.218:/opt/cassava/webroot/

# CassavaFE build
rsync -avz --delete CassavaFE/dist/ uet@112.137.129.218:/opt/cassava/webroot/cassava/

# Backend WAR
rsync -avz cassavaBE/target/demo1-0.0.1-SNAPSHOT.war uet@112.137.129.218:/opt/cassava/cassava-be.war
```

Kiểm tra trên server:

```bash
ssh uet@112.137.129.218 'ls -la /opt/cassava/ /opt/cassava/webroot/'
```

---

## 4. Cài systemd service

Upload file service:

```bash
scp deploy/systemd/cassava-be.service uet@112.137.129.218:/tmp/
```

Trên server:

```bash
sudo mv /tmp/cassava-be.service /etc/systemd/system/
sudo systemctl daemon-reload
sudo systemctl enable --now cassava-be
sudo systemctl status cassava-be
```

Xem log realtime:

```bash
sudo journalctl -u cassava-be -f
```

Kiểm tra BE đã chạy (localhost):

```bash
curl -s http://127.0.0.1:8081/mongo/field | head -c 200
```

---

## 5. Cài nginx

Upload nginx config:

```bash
scp deploy/nginx/cassava.conf uet@112.137.129.218:/tmp/
```

Trên server:

```bash
sudo mv /tmp/cassava.conf /etc/nginx/sites-available/cassava
sudo ln -sf /etc/nginx/sites-available/cassava /etc/nginx/sites-enabled/cassava

# Xóa default site nếu có
sudo rm -f /etc/nginx/sites-enabled/default

# Test config và reload
sudo nginx -t
sudo systemctl reload nginx
```

Test:

- Mở trình duyệt: `http://112.137.129.218/` → landing
- Click "Truy cập" trên card Sắn → `http://112.137.129.218/cassava/` → CassavaFE
- Đăng nhập → thấy FE gọi `/cassava/api/...` thành công

---

## 6. Quy trình cập nhật (sau lần đầu)

### Chỉ FE thay đổi

```bash
cd CassavaFE && npm run build
rsync -avz --delete dist/ uet@112.137.129.218:/opt/cassava/webroot/cassava/
```

Không cần restart gì cả — nginx serve file tĩnh.

### Chỉ BE thay đổi

```bash
cd cassavaBE && mvn clean package -DskipTests
rsync -avz target/demo1-0.0.1-SNAPSHOT.war uet@112.137.129.218:/opt/cassava/cassava-be.war
ssh uet@112.137.129.218 'sudo systemctl restart cassava-be'
```

### Landing page thay đổi

```bash
rsync -avz --delete landing/ uet@112.137.129.218:/opt/cassava/webroot/
```

---

## 7. Troubleshoot

| Triệu chứng | Kiểm tra |
|---|---|
| Landing 404 | `ls /opt/cassava/webroot/index.html` có tồn tại? |
| `/cassava/` blank page | DevTools Network: file JS/CSS có load từ `/cassava/assets/...` không? Nếu 404 → rebuild FE (base path sai) |
| API 502 Bad Gateway | `sudo systemctl status cassava-be` — BE có chạy không? `curl http://127.0.0.1:8081/` có trả về gì không? |
| API 504 timeout | Mô phỏng chạy lâu → tăng `proxy_read_timeout` trong nginx config |
| BE lỗi MongoDB | Test connect: `mongosh mongodb://admin:uet%402026@112.137.129.218:27017/iot_agriculture?authSource=admin` |
| Firebase log spam | Đã được disable graceful (file key không tồn tại → skip). Nếu vẫn thấy stack trace về firebase, kiểm tra `FirebaseInitialization.java` |

Xem log BE chi tiết:

```bash
sudo journalctl -u cassava-be -n 200 --no-pager
tail -f /var/log/cassava/app.log
```

---

## 8. Thêm hệ thống cây mới (ví dụ tomato)

Sau này khi có TomatoFE + TomatoBE:

1. Build TomatoFE với `base: '/tomato/'` và `VITE_API_BASE=/tomato/api`
2. Deploy FE vào `/opt/cassava/webroot/tomato/`
3. Run TomatoBE trên port khác (8082)
4. Thêm block vào `/etc/nginx/sites-available/cassava`:

```nginx
location /tomato/api/ {
    proxy_pass http://127.0.0.1:8082/;
    # ... proxy headers như block cassava
}
location /tomato/ {
    try_files $uri $uri/ /tomato/index.html;
}
```

5. Sửa `landing/index.html`: đổi card Tomato từ `disabled` thành `active`, link `/tomato/`

---

## 9. Ghi chú bảo mật

- Hiện tại chạy HTTP, JWT đi plain text. Có domain thì chuyển sang HTTPS bằng Let's Encrypt (`certbot --nginx`)
- Spring Boot bind 127.0.0.1 → không thể truy cập trực tiếp port 8081 từ internet
- MongoDB đang public (112.137.129.218:27017) — cân nhắc đổi sang bind LAN hoặc thêm iptables rule chỉ cho phép localhost
- Firewall: chỉ mở 80 (HTTP), 22 (SSH). Không mở 8081, 27017 cho internet
