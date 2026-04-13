/**import React from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Button, Typography } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';

const { Title } = Typography;

const WeatherDetail = () => {
  const { sensorId } = useParams(); // Lấy ID cảm biến từ URL
  const navigate = useNavigate();

  return (
    <div style={{ padding: '24px' }}>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} style={{ marginBottom: 16 }}>
        Quay lại
      </Button>
      
      <Card title={`Đồ thị chi tiết: ${sensorId.toUpperCase()}`}>
        <div style={{ height: '400px', background: '#f0f2f5', display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
          {/* Kiên sẽ dùng thư viện Recharts hoặc Chart.js nhúng vào đây 
          <Title level={4} type="secondary">
            Biểu đồ đường (Line Chart) cho {sensorId} sẽ hiển thị ở đây
          </Title>
        </div>
      </Card>
    </div>
  );
};

export default WeatherDetail;
*/
import React, { useEffect, useState } from 'react';
import { useParams, useNavigate } from 'react-router-dom';
import { Card, Button, Typography, Spin, message, Empty } from 'antd';
import { ArrowLeftOutlined, LoadingOutlined } from '@ant-design/icons';
// 1. Import các thành phần của Recharts
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
  AreaChart,
  Area
} from 'recharts';
import api from '../../services/api';

const { Title, Text } = Typography;

const WeatherDetail = () => {
  const { sensorId } = useParams(); // Lấy sensorId (ví dụ: temperature)
  // Lấy fieldId từ query string hoặc nếu cậu đã truyền qua state của navigate
  // Tạm thời mình giả định cậu cần fieldId để gọi API. 
  // Nếu route là /weather/:fieldId/:sensorId thì lấy được cả 2.
  const navigate = useNavigate();

  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);

  // 2. Hàm gọi API lấy dữ liệu lịch sử
 /*  const fetchHistory = async () => {
    setLoading(true);
    try {
      // Gọi API với query params. Lưu ý: fieldId nên được lấy động từ URL hoặc state
      const response = await authService.get(`/sensor-values/history`, {
        params: {
          fieldId: 'fieldTest', // Cậu nên thay bằng biến động
          sensorId: sensorId
        }
      });
      
      // Recharts cần dữ liệu sắp xếp từ Cũ -> Mới để vẽ đường thẳng
      // Mà BE mình đang để Desc (Mới nhất lên đầu) nên cần reverse lại
      const chartData = response.data.reverse().map(item => ({
        time: new Date(item.time).toLocaleTimeString('vi-VN', { hour: '2-digit', minute: '2-digit' }),
        value: item.value,
        fullTime: new Date(item.time).toLocaleString('vi-VN')
      }));
      
      setData(chartData);
    } catch (error) {
      console.error("Lỗi tải dữ liệu biểu đồ:", error);
      message.error("Không thể tải dữ liệu lịch sử cảm biến");
    } finally {
      setLoading(false);
    }
  };
  */
 const SOIL_SERIES = [
    { id: 'humidity30', label: 'Độ ẩm đất 30cm', color: '#13c2c2' },
    { id: 'humidity60', label: 'Độ ẩm đất 60cm', color: '#08979c' },
  ];
  const isSoilHumidity = sensorId === 'soilHumidity';

  const fetchSoilHumidity = async () => {
    setLoading(true);
    try {
      const responses = await Promise.all(
        SOIL_SERIES.map((s) =>
          api.get(`/sensor-values/history`, {
            params: { fieldId: 'fieldTest', sensorId: s.id },
          })
        )
      );

      const buckets = new Map();
      const cutoff = (() => {
        const latest = Math.max(
          ...responses
            .flatMap((r) => (r.data && r.data[0] ? [new Date(r.data[0].time).getTime()] : []))
        );
        return Number.isFinite(latest) ? latest - 24 * 60 * 60 * 1000 : 0;
      })();

      responses.forEach((res, idx) => {
        const key = SOIL_SERIES[idx].id;
        (res.data || []).forEach((item) => {
          const t = new Date(item.time).getTime();
          if (t < cutoff) return;
          if (!buckets.has(t)) {
            buckets.set(t, {
              t,
              time: new Date(item.time).toLocaleTimeString('vi-VN', {
                hour: '2-digit',
                minute: '2-digit',
                timeZone: 'Asia/Ho_Chi_Minh',
              }),
              fullTime: new Date(item.time).toLocaleString('vi-VN', {
                timeZone: 'Asia/Ho_Chi_Minh',
              }),
            });
          }
          buckets.get(t)[key] = item.value;
        });
      });

      setData(Array.from(buckets.values()).sort((a, b) => a.t - b.t));
    } catch {
      message.error('Lỗi tải dữ liệu lịch sử');
    } finally {
      setLoading(false);
    }
  };

 const fetchHistory = async () => {
  if (isSoilHumidity) {
    return fetchSoilHumidity();
  }
  setLoading(true);
  try {
    const response = await api.get(`/sensor-values/history`, {
      params: { fieldId: 'fieldTest', sensorId: sensorId }
    });

    if (response.data && response.data.length > 0) {
      // 1. Tìm thời gian của bản ghi mới nhất (vì BE trả về Desc nên phần tử 0 là mới nhất)
      const latestTime = new Date(response.data[0].time).getTime();
      const twentyFourHoursBeforeLatest = latestTime - (24 * 60 * 60 * 1000);

      // 2. Lọc dữ liệu trong khoảng 24h tính từ bản ghi mới nhất trở về trước
      const chartData = response.data
        .filter(item => {
          const itemTime = new Date(item.time).getTime();
          return itemTime >= twentyFourHoursBeforeLatest;
        })
        .reverse() // Đảo lại để vẽ biểu đồ từ trái sang phải
        .map(item => ({
          // Chuyển về giờ Việt Nam (HH:mm)
          time: new Date(item.time).toLocaleTimeString('vi-VN', { 
            hour: '2-digit', 
            minute: '2-digit',
            timeZone: 'Asia/Ho_Chi_Minh' 
          }),
          value: item.value,
          fullTime: new Date(item.time).toLocaleString('vi-VN', { timeZone: 'Asia/Ho_Chi_Minh' })
        }));

      setData(chartData);
    } else {
      setData([]);
    }
  } catch (error) {
    message.error("Lỗi tải dữ liệu lịch sử");
  } finally {
    setLoading(false);
  }
};

  useEffect(() => {
    fetchHistory();
    // (Tùy chọn) Cứ 30s cập nhật biểu đồ 1 lần cho "Realtime"
   // const interval = setInterval(fetchHistory, 30000);
   // return () => clearInterval(interval);
  }, [sensorId]);
  

  // 3. Cấu hình màu sắc cho từng loại biểu đồ
  const getChartColor = (id) => {
    switch(id) {
      case 'temperature': return '#ff4d4f';
      case 'relativeHumidity': return '#1890ff';
      case 'humidity30': return '#13c2c2';
      case 'humidity60': return '#08979c';
      case 'rainfall':
      case 'rain': return '#52c41a';
      default: return '#722ed1';
    }
  };

  const SENSOR_NAMES_VI = {
    temperature: 'Nhiệt độ môi trường',
    relativeHumidity: 'Độ ẩm không khí',
    humidity30: 'Độ ẩm đất 30cm',
    humidity60: 'Độ ẩm đất 60cm',
    soilHumidity: 'Độ ẩm đất (30cm & 60cm)',
    rain: 'Lượng mưa tích lũy',
    rainfall: 'Lượng mưa tích lũy',
    radiation: 'Bức xạ mặt trời',
    wind: 'Tốc độ gió',
    wind_speed: 'Tốc độ gió',
  };
  const sensorNameVi = SENSOR_NAMES_VI[sensorId] || sensorId;

  return (
    <div style={{ padding: '24px' }}>
      <Button icon={<ArrowLeftOutlined />} onClick={() => navigate(-1)} style={{ marginBottom: 16 }}>
        Quay lại trạm quan trắc
      </Button>
      
      <Card 
        title={<Title level={4} style={{ margin: 0 }}>Biểu đồ trực quan: {sensorNameVi}</Title>}
        extra={<Text type="secondary">Dữ liệu 24h qua</Text>}
      >
        {loading ? (
          <div style={{ height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
          </div>
        ) : data.length > 0 && isSoilHumidity ? (
          <div style={{ width: '100%', height: 400 }}>
            <ResponsiveContainer>
              <LineChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" />
                <YAxis unit="%" domain={[0, 100]} />
                <Tooltip
                  labelFormatter={(_, payload) => payload[0]?.payload?.fullTime}
                />
                <Legend />
                {SOIL_SERIES.map((s) => (
                  <Line
                    key={s.id}
                    type="monotone"
                    dataKey={s.id}
                    name={s.label}
                    stroke={s.color}
                    dot={false}
                    connectNulls
                    isAnimationActive={false}
                  />
                ))}
              </LineChart>
            </ResponsiveContainer>
          </div>
        ) : data.length > 0 ? (
          <div style={{ width: '100%', height: 400 }}>
            <ResponsiveContainer>
              <AreaChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                <defs>
                  <linearGradient id="colorValue" x1="0" y1="0" x2="0" y2="1">
                    <stop offset="5%" stopColor={getChartColor(sensorId)} stopOpacity={0.8}/>
                    <stop offset="95%" stopColor={getChartColor(sensorId)} stopOpacity={0}/>
                  </linearGradient>
                </defs>
                <CartesianGrid strokeDasharray="3 3" vertical={false} />
                <XAxis dataKey="time" />
                <YAxis />
                <Tooltip 
                  labelStyle={{ fontWeight: 'bold' }}
                  formatter={(value) => [`${value}`, 'Giá trị']}
                  labelFormatter={(label, payload) => payload[0]?.payload?.fullTime}
                />
                <Area 
                  type="monotone" 
                  dataKey="value" 
                  stroke={getChartColor(sensorId)} 
                  fillOpacity={1} 
                  fill="url(#colorValue)" 
                  animationDuration={1500}
                />
              </AreaChart>
            </ResponsiveContainer>
          </div>
        ) : (
          <Empty description="Không có dữ liệu lịch sử cho cảm biến này" />
        )}
      </Card>
    </div>
  );
};

export default WeatherDetail;