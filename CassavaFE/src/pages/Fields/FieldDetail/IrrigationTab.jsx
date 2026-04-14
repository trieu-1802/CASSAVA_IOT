// src/pages/Fields/FieldDetail/IrrigationTab.jsx
import React, { useEffect, useState } from 'react';
import { Card, Typography, Spin, Empty, message } from 'antd';
import { LoadingOutlined } from '@ant-design/icons';
import {
  LineChart,
  Line,
  XAxis,
  YAxis,
  CartesianGrid,
  Tooltip,
  Legend,
  ResponsiveContainer,
} from 'recharts';
import api from '../../../services/api';

const { Title, Text } = Typography;

const SOIL_SENSORS = [
  { id: 'humidity30', label: 'Độ ẩm đất 30cm', color: '#1890ff' },
  { id: 'humidity60', label: 'Độ ẩm đất 60cm', color: '#722ed1' },
];

const IrrigationTab = ({ fieldId }) => {
  const [data, setData] = useState([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    if (!fieldId) return;

    const fetchSoilHumidity = async () => {
      setLoading(true);
      try {
        const responses = await Promise.all(
          SOIL_SENSORS.map((s) =>
            api.get('/sensor-values/history', {
              params: { fieldId, sensorId: s.id },
            })
          )
        );

        // Gộp theo thời gian (làm tròn phút) để vẽ 2 đường cùng trục X
        const buckets = new Map();
        responses.forEach((res, idx) => {
          const sensorKey = SOIL_SENSORS[idx].id;
          (res.data || []).forEach((item) => {
            const t = new Date(item.time).getTime();
            if (!buckets.has(t)) {
              buckets.set(t, {
                time: t,
                timeLabel: new Date(item.time).toLocaleString('vi-VN', {
                  timeZone: 'Asia/Ho_Chi_Minh',
                  month: '2-digit',
                  day: '2-digit',
                  hour: '2-digit',
                  minute: '2-digit',
                }),
              });
            }
            buckets.get(t)[sensorKey] = item.value;
          });
        });

        const merged = Array.from(buckets.values()).sort((a, b) => a.time - b.time);
        setData(merged);
      } catch (err) {
        console.error('Lỗi tải dữ liệu độ ẩm đất:', err);
        message.error('Không thể tải dữ liệu độ ẩm đất.');
      } finally {
        setLoading(false);
      }
    };

    fetchSoilHumidity();
  }, [fieldId]);

  return (
    <div style={{ padding: '20px 0' }}>
      <Title level={4}>Biểu đồ độ ẩm đất theo độ sâu</Title>
      <Card>
        {loading ? (
          <div style={{ height: 400, display: 'flex', alignItems: 'center', justifyContent: 'center' }}>
            <Spin indicator={<LoadingOutlined style={{ fontSize: 40 }} spin />} />
          </div>
        ) : data.length === 0 ? (
          <Empty description="Chưa có dữ liệu độ ẩm đất 30cm / 60cm cho cánh đồng này" />
        ) : (
          <>
            <Text type="secondary">
              So sánh độ ẩm đất ở độ sâu 30cm và 60cm. Đơn vị: %.
            </Text>
            <div style={{ width: '100%', height: 400, marginTop: 12 }}>
              <ResponsiveContainer>
                <LineChart data={data} margin={{ top: 10, right: 30, left: 0, bottom: 0 }}>
                  <CartesianGrid strokeDasharray="3 3" vertical={false} />
                  <XAxis dataKey="timeLabel" />
                  <YAxis unit="%" domain={[0, 100]} />
                  <Tooltip />
                  <Legend />
                  {SOIL_SENSORS.map((s) => (
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
          </>
        )}
      </Card>
    </div>
  );
};

export default IrrigationTab;
