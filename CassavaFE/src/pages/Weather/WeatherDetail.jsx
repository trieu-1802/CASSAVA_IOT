import React from 'react';
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
          {/* Kiên sẽ dùng thư viện Recharts hoặc Chart.js nhúng vào đây */}
          <Title level={4} type="secondary">
            Biểu đồ đường (Line Chart) cho {sensorId} sẽ hiển thị ở đây
          </Title>
        </div>
      </Card>
    </div>
  );
};

export default WeatherDetail;