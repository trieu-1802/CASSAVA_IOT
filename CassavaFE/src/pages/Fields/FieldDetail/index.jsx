// src/pages/Fields/FieldDetail/index.jsx
import React from 'react';
import { Tabs, Card, Typography, Breadcrumb, Button } from 'antd';
import { ArrowLeftOutlined } from '@ant-design/icons';
import { useParams, useNavigate } from 'react-router-dom';

// Import 4 component Tab vừa tạo
import IrrigationTab from './IrrigationTab';
import YieldTab from './YieldTab';
import HistoryTab from './HistoryTab';
import DiseaseTab from './DiseaseTab';

const { Title } = Typography;

const FieldDetailIndex = () => {
  const { id } = useParams();
  const navigate = useNavigate();

  // Nhúng các component con vào cấu trúc Tabs
  const tabItems = [
    { key: '1', label: 'Theo dõi tưới tiêu', children: <IrrigationTab /> },
    { key: '2', label: 'Dự đoán sản lượng', children: <YieldTab /> },
    { key: '3', label: 'Lịch sử tưới', children: <HistoryTab /> },
    { key: '4', label: 'Tình trạng bệnh', children: <DiseaseTab /> },
  ];

  return (
    <div>
      <Breadcrumb style={{ marginBottom: '16px' }}>
        <Breadcrumb.Item>
          <a onClick={() => navigate('/fields')}>Danh sách cánh đồng</a>
        </Breadcrumb.Item>
        <Breadcrumb.Item>Chi tiết cánh đồng #{id}</Breadcrumb.Item>
      </Breadcrumb>

      <Card>
        <div style={{ display: 'flex', alignItems: 'center', marginBottom: '20px' }}>
          <Button 
            type="text" 
            icon={<ArrowLeftOutlined />} 
            onClick={() => navigate('/fields')} 
            style={{ marginRight: '16px' }}
          />
          <Title level={3} style={{ margin: 0 }}>Thông tin cánh đồng #{id}</Title>
        </div>

        <Tabs defaultActiveKey="1" items={tabItems} />
      </Card>
    </div>
  );
};

export default FieldDetailIndex;