// src/pages/Weather/WeatherDashboard.jsx
//import React from 'react';
//import { Card, Row, Col, Typography, Statistic } from 'antd';
//import { 
 // CloudOutlined, 
 // FireOutlined, 
 // ThunderboltOutlined,
 // CompassOutlined,
 // DashboardOutlined
//} from '@ant-design/icons';

//const { Title } = Typography;

//const WeatherDashboard = () => {
 // return (
  // <div style={{ padding: '24px' }}>
    //  <Title level={3}>Dữ liệu Thời tiết trạm IoT</Title>
    //  <p>Cập nhật theo thời gian thực từ các cảm biến ngoài trời.</p>

      {/* Hàng Card hiển thị 5 thông số */}
 {/*     <Row gutter={[16, 16]}>
        <Col span={8}>
          <Card>
            <Statistic
              title="Nhiệt độ"
              value={32.5}
              precision={1}
              valueStyle={{ color: '#cf1322' }}
              prefix={<FireOutlined />}
              suffix="°C"
            />
          </Card>
        </Col>
        
        <Col span={8}>
          <Card>
            <Statistic
              title="Độ ẩm tương đối"
              value={78}
              valueStyle={{ color: '#096dd9' }}
              prefix={<CloudOutlined />}
              suffix="%"
            />
          </Card>
        </Col>

        <Col span={8}>
          <Card>
            <Statistic
              title="Lượng mưa"
              value={15.2}
              precision={1}
              valueStyle={{ color: '#3f6600' }}
              prefix={<DashboardOutlined />}
              suffix="mm"
            />
          </Card>
        </Col>

        <Col span={12}>
          <Card>
            <Statistic
              title="Bức xạ mặt trời"
              value={850}
              valueStyle={{ color: '#d48806' }}
              prefix={<ThunderboltOutlined />}
              suffix="W/m²"
            />
          </Card>
        </Col>

        <Col span={12}>
          <Card>
            <Statistic
              title="Tốc độ gió"
              value={3.5}
              precision={1}
              valueStyle={{ color: '#531dab' }}
              prefix={<CompassOutlined />}
              suffix="m/s"
            />
          </Card>
        </Col>
      </Row>
  */}
      {/* Chừa sẵn khung để sau này nhúng biểu đồ thời tiết */}
    {/*  <Card title="Biểu đồ xu hướng thời tiết 7 ngày qua" style={{ marginTop: '24px' }}>
        <div style={{ height: '300px', display: 'flex', alignItems: 'center', justifyContent: 'center', background: '#fafafa', color: '#999' }}>
          (Khu vực hiển thị biểu đồ đồ thị thời tiết - Cần cài đặt thư viện Recharts để vẽ)
        </div>
      </Card>
    </div>
  );
};

export default WeatherDashboard;
*/}
/** 
import React from 'react';
import { Card, List, Typography, Button, Tag, Space } from 'antd';
import { 
  CloudOutlined, 
  FireOutlined, 
  ThunderboltOutlined,
  CompassOutlined,
  DashboardOutlined,
  LineChartOutlined,
  RightOutlined
} from '@ant-design/icons';
import fieldService from '../../services/fieldService';
import { useNavigate } from 'react-router-dom';

const { Title, Text } = Typography;

const WeatherDashboard = () => {
  const navigate = useNavigate();
  
  // Dữ liệu giả lập cho 5 loại cảm biến
  const sensors = [
    { id: 'temperature', name: 'Nhiệt độ môi trường', value: 32.5, unit: '°C', icon: <FireOutlined style={{ color: '#cf1322' }} />, color: 'red' },
    { id: 'humidity', name: 'Độ ẩm không khí', value: 78, unit: '%', icon: <CloudOutlined style={{ color: '#096dd9' }} />, color: 'blue' },
    { id: 'rainfall', name: 'Lượng mưa tích lũy', value: 15.2, unit: 'mm', icon: <DashboardOutlined style={{ color: '#3f6600' }} />, color: 'green' },
    { id: 'radiation', name: 'Bức xạ mặt trời', value: 850, unit: 'W/m²', icon: <ThunderboltOutlined style={{ color: '#d48806' }} />, color: 'warning' },
    { id: 'wind_speed', name: 'Tốc độ gió', value: 3.5, unit: 'm/s', icon: <CompassOutlined style={{ color: '#531dab' }} />, color: 'purple' },
  ];
  
  return (
    <div style={{ padding: '24px' }}>
      <Card 
        title={<Title level={3} style={{ margin: 0 }}>Trạm quan trắc khí tượng IoT</Title>}
        extra={<Tag color="green">Đang kết nối</Tag>}
      >
        <List
          itemLayout="horizontal"
          dataSource={sensors}
          renderItem={(item) => (
            <List.Item
              style={{ padding: '20px 0' }}
              actions={[
                <Button 
                  type="primary" 
                  ghost 
                  icon={<LineChartOutlined />} 
                  onClick={() => navigate(`/weather/detail/${item.id}`)}
                >
                  Xem đồ thị <RightOutlined />
                </Button>
              ]}
            >
              <List.Item.Meta
                avatar={
                  <div style={{ 
                    fontSize: '24px', 
                    background: '#f5f5f5', 
                    padding: '12px', 
                    borderRadius: '8px',
                    display: 'flex',
                    alignItems: 'center'
                  }}>
                    {item.icon}
                  </div>
                }
                title={<Text strong style={{ fontSize: '16px' }}>{item.name}</Text>}
                description={
                  <Space direction="vertical">
                    <Text type="secondary">Cảm biến hoạt động bình thường</Text>
                    <Title level={4} style={{ margin: 0 }}>
                      {item.value} <small>{item.unit}</small>
                    </Title>
                  </Space>
                }
              />
            </List.Item>
          )}
        />
      </Card>
    </div>
  );
};

export default WeatherDashboard;
*/
import React, { useEffect, useState } from 'react';
import { Card, List, Typography, Button, Tag, Space, Spin, message } from 'antd';
import { 
  CloudOutlined, 
  FireOutlined, 
  ThunderboltOutlined,
  CompassOutlined,
  DashboardOutlined,
  LineChartOutlined,
  RightOutlined,
  LoadingOutlined
} from '@ant-design/icons';
import { useNavigate, useParams } from 'react-router-dom';
import fieldService from '../../services/fieldService';

const { Title, Text } = Typography;

const WeatherDashboard = () => {
  const navigate = useNavigate();
  const { fieldId } = useParams(); // Lấy ID cánh đồng từ URL
  const [sensors, setSensors] = useState([]);
  const [loading, setLoading] = useState(true);
  const [fieldName, setFieldName] = useState('');

  useEffect(() => {
    if (!fieldId) return;
    fieldService
      .get(`/field/${fieldId}`)
      .then((res) => setFieldName(res.data?.name || fieldId))
      .catch(() => setFieldName(fieldId));
  }, [fieldId]);

  // Hàm bổ trợ để map sensorId từ BE sang giao diện (Icon, Tên, Đơn vị)
  const getSensorConfig = (sensorId) => {
    const airHumidity = { name: 'Độ ẩm không khí', unit: '%', icon: <CloudOutlined style={{ color: '#096dd9' }} /> };
    const rainCfg = { name: 'Lượng mưa tích lũy', unit: 'mm', icon: <DashboardOutlined style={{ color: '#3f6600' }} /> };
    const windCfg = { name: 'Tốc độ gió', unit: 'm/s', icon: <CompassOutlined style={{ color: '#531dab' }} /> };
    const configs = {
      temperature: { name: 'Nhiệt độ môi trường', unit: '°C', icon: <FireOutlined style={{ color: '#cf1322' }} /> },
      relativeHumidity: airHumidity,
      humidity: airHumidity, // alias cho dữ liệu cũ trước khi đổi tên
      soilHumidity: { name: 'Độ ẩm đất (30cm & 60cm)', unit: '%', icon: <CloudOutlined style={{ color: '#13c2c2' }} /> },
      rain: rainCfg,
      rainfall: rainCfg,
      radiation: { name: 'Bức xạ mặt trời', unit: 'W/m²', icon: <ThunderboltOutlined style={{ color: '#d48806' }} /> },
      wind: windCfg,
      wind_speed: windCfg,
    };
    // Trả về config tương ứng hoặc giá trị mặc định nếu không khớp
    return configs[sensorId] || { name: sensorId, unit: '', icon: <DashboardOutlined /> };
  };

  useEffect(() => {
    const fetchSensors = async () => {
      setLoading(true);
      try {
        // Gọi API lấy sensor theo fieldId
        // Lưu ý: Cậu kiểm tra lại chính xác endpoint BE của cậu nhé
        const response = await fieldService.get(`/field/${fieldId}/sensor`);
        // Gộp humidity30 + humidity60 thành 1 mục hiển thị "soilHumidity"
        const raw = response.data || [];
        const hasSoil30 = raw.some((s) => s.sensorId === 'humidity30');
        const hasSoil60 = raw.some((s) => s.sensorId === 'humidity60');
        const filtered = raw.filter(
          (s) => s.sensorId !== 'humidity30' && s.sensorId !== 'humidity60'
        );
        if (hasSoil30 || hasSoil60) {
          filtered.push({ id: 'soil-humidity', sensorId: 'soilHumidity' });
        }
        setSensors(filtered);
      } catch (error) {
        console.error("Lỗi tải sensor:", error);
        message.error("Không thể lấy danh sách cảm biến của cánh đồng này!");
      } finally {
        setLoading(false);
      }
    };

    if (fieldId) {
      fetchSensors();
    }
  }, [fieldId]);

  if (loading) {
    return (
      <div style={{ textAlign: 'center', padding: '100px' }}>
        <Spin indicator={<LoadingOutlined style={{ fontSize: 48 }} spin />} />
        <div style={{ marginTop: 16 }}>Đang tải danh sách cảm biến...</div>
      </div>
    );
  }

  return (
    <div style={{ padding: '24px' }}>
      <Card 
        title={
          <Space>
            <Title level={3} style={{ margin: 0 }}>Trạm quan trắc: {fieldName || '...'}</Title>
            <Tag color="blue">Cánh đồng {fieldName || fieldId}</Tag>
          </Space>
        }
        extra={<Tag color="green">Đang kết nối</Tag>}
      >
        <List
          itemLayout="horizontal"
          dataSource={sensors}
          locale={{ emptyText: 'Cánh đồng này hiện chưa được lắp đặt cảm biến.' }}
          renderItem={(item) => {
            const config = getSensorConfig(item.sensorId);
            return (
              <List.Item
                style={{ padding: '20px 0' }}
                actions={[
                  <Button 
                    type="primary" 
                    ghost 
                    icon={<LineChartOutlined />} 
                    onClick={() => navigate(`/weather/detail/${item.sensorId}`)}
                  >
                    Xem đồ thị <RightOutlined />
                  </Button>
                ]}
              >
                <List.Item.Meta
                  avatar={
                    <div style={{ 
                      fontSize: '24px', 
                      background: '#f5f5f5', 
                      padding: '12px', 
                      borderRadius: '8px',
                      display: 'flex',
                      alignItems: 'center'
                    }}>
                      {config.icon}
                    </div>
                  }
                  title={<Text strong style={{ fontSize: '16px' }}>{config.name}</Text>}
                  description={
                    <Space direction="vertical">
                      <Text type="secondary">ID thiết bị: {item.id}</Text>
                      <Title level={4} style={{ margin: 0 }}>
                        {/* Hiển thị giá trị thực tế nếu BE có trả về, không thì để placeholder */}
                        {item.lastValue || '--'} <small>{config.unit}</small>
                      </Title>
                    </Space>
                  }
                />
              </List.Item>
            );
          }}
        />
      </Card>
      
      <Button 
        style={{ marginTop: 16 }} 
        onClick={() => navigate('/fields')}
      >
        Quay lại danh sách cánh đồng
      </Button>
    </div>
  );
};

export default WeatherDashboard;
